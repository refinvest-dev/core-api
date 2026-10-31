package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.tradingreview.domain.DeletionRequest
import com.refinvest.core.tradingreview.domain.valueobject.*
import com.refinvest.core.tradingreview.port.deletion.DeletionRequestExecution
import com.refinvest.core.tradingreview.port.RawArtifactStorage
import com.refinvest.core.tradingreview.port.TradingAnalyticsArtifactStore
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Clock
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

@Component
@ConditionalOnProperty(prefix = "refinvest.trading-review", name = ["deletion-enabled"], havingValue = "true", matchIfMissing = true)
class TradingDeletionWorker(
    private val jdbc: JdbcTemplate,
    private val transactions: TransactionTemplate,
    private val storage: RawArtifactStorage,
    private val clock: Clock,
    private val analyticsArtifacts: TradingAnalyticsArtifactStore? = null,
    private val execution: DeletionRequestExecution = JdbcDeletionRequestExecution(jdbc),
) {
    @Scheduled(fixedDelay = 5000)
    fun purgePending() {
        repeat(20) {
            var attempted: DeletionRequest? = null
            val result = try {
                transactions.execute {
                    val root = execution.lockNext() ?: return@execute null
                    attempted = root
                    val request = root.toRequest()
                    val now = clock.instant()
                    var next = root
                    if (root.state != DeletionRequestState.REQUESTED && jdbc.queryForObject(
                            "SELECT NOT EXISTS(SELECT 1 FROM trading_deletion_worksets WHERE request_id=?)",
                            Boolean::class.java, request.id) == true) {
                        next = captureWorkset(next, request, now)
                        // Pre-V13 workers could commit PURGING_OBJECTS while primary rows still exist.
                        // Resume that phase without reversing state or inventing historical checkpoints.
                        if (root.state == DeletionRequestState.PURGING_OBJECTS) {
                            next = purgePrimary(next, request, now)
                            check(execution.commit(root, next))
                            return@execute Step(next, false)
                        }
                    }
                    when (root.state) {
                        DeletionRequestState.REQUESTED -> next = root.start(now)
                        DeletionRequestState.CANCELLING_JOBS -> {
                            next = next.beginPrimaryPurge(now)
                        }
                        DeletionRequestState.PURGING_PRIMARY -> {
                            next = purgePrimary(next, request, now).beginObjectPurge(now)
                        }
                        DeletionRequestState.PURGING_OBJECTS -> {
                            val tasks = jdbc.query("""SELECT artifact_id, object_key FROM trading_deletion_object_tasks
                                WHERE request_id=? AND verified_at IS NULL ORDER BY artifact_id LIMIT 1""",
                                { r, _ -> r.getLong(1) to r.getString(2) }, request.id)
                            for ((id, key) in tasks) {
                                check(key != null) { "Missing deletion locator" }
                                storage.delete(key)
                                check(!storage.exists(key)) { "Object deletion incomplete" }
                                jdbc.update("""UPDATE trading_source_artifacts SET retention_status='DELETED',
                                    object_key=NULL, wrapped_dek=NULL, key_version=NULL, filename=NULL,
                                    deleted_at=?, deletion_failure=NULL, version=version+1 WHERE id=?""", Timestamp.from(now), id)
                                jdbc.update("""UPDATE trading_deletion_object_tasks SET verified_at=?
                                    WHERE request_id=? AND artifact_id=?""", Timestamp.from(now), request.id, id)
                            }
                            if (tasks.isNotEmpty()) next = next.recordExecutionProgress(now)
                            val remaining = jdbc.queryForObject("""SELECT EXISTS(SELECT 1 FROM trading_deletion_object_tasks
                                WHERE request_id=? AND verified_at IS NULL)""", Boolean::class.java, request.id) == true
                            if (!remaining && !hasUnfinishedChildren(request)) {
                                if (DeletionPurgeComponent.RAW_OBJECTS !in next.verifiedAbsent)
                                    next = next.verifyAbsent(DeletionPurgeComponent.RAW_OBJECTS, now)
                                val keysRemain = jdbc.queryForObject("""SELECT EXISTS(SELECT 1 FROM trading_source_artifacts a
                                    JOIN trading_deletion_object_tasks t ON t.artifact_id=a.id
                                    WHERE t.request_id=? AND (a.wrapped_dek IS NOT NULL OR a.key_version IS NOT NULL OR a.object_key IS NOT NULL))""",
                                    Boolean::class.java, request.id) == true
                                if (!keysRemain && DeletionPurgeComponent.RAW_KEYS !in next.verifiedAbsent)
                                    next = next.verifyAbsent(DeletionPurgeComponent.RAW_KEYS, now)
                                if (next.verifiedAbsent.keys.containsAll(next.checklist.requiredComponents))
                                    next = next.complete(now)
                            }
                        }
                        else -> error("Terminal request cannot be claimed")
                    }
                    if (next !== root) check(execution.commit(root, next)) { "Stale deletion progress" }
                    Step(next, root.state == DeletionRequestState.PURGING_OBJECTS &&
                        !next.state.terminal && jdbc.queryForObject("""SELECT NOT EXISTS(SELECT 1
                            FROM trading_deletion_object_tasks WHERE request_id=? AND verified_at IS NULL)""",
                            Boolean::class.java, request.id) == true)
                }
            } catch (_: Exception) {
                attempted?.let(::recordFailure)
                return
            } ?: return
            if (result.unverified) {
                recordFailure(result.request)
                return
            }
        }
    }

    private fun captureWorkset(root: DeletionRequest, request: Request, now: java.time.Instant): DeletionRequest {
        var next = root
        // Preserve locators before any primary rows/keys can disappear.
        jdbc.update("""INSERT INTO trading_deletion_object_tasks
            (request_id,artifact_id,object_key,verified_at)
            SELECT ?,a.id,a.object_key,CASE WHEN a.retention_status='DELETED'
                AND a.object_key IS NULL AND a.wrapped_dek IS NULL AND a.key_version IS NULL
                THEN ?::timestamptz ELSE NULL END
            FROM trading_source_artifacts a JOIN trading_import_sessions s ON s.id=a.import_session_id
            JOIN trading_books b ON b.id=s.trading_book_id WHERE """ + scopeClause(request) +
            " ON CONFLICT DO NOTHING", request.id, Timestamp.from(now), request.target)
        val sessions = sessionIds(request)
        jdbc.update("""INSERT INTO trading_deletion_session_tasks(request_id,session_id,book_id,account_id)
            SELECT ?,s.id,b.id,b.account_id FROM trading_import_sessions s JOIN trading_books b ON b.id=s.trading_book_id
            WHERE s.id IN ($sessions) ON CONFLICT DO NOTHING""", request.id, request.target)
        jdbc.update("""INSERT INTO trading_deletion_runtime_tasks(request_id,run_id,attempt_token,compute_job_id,deletion_generation,current_deletion_generation)
            SELECT ?,r.id,r.attempt_token,r.compute_job_id,r.deletion_generation,
                GREATEST(r.deletion_generation,s.deletion_generation,b.deletion_generation,acct.deletion_generation,${root.deletionGeneration})
            FROM trading_normalization_runs r JOIN trading_import_sessions s ON s.id=r.import_session_id
            JOIN trading_books b ON b.id=s.trading_book_id JOIN trading_accounts acct ON acct.id=b.account_id
            WHERE r.import_session_id IN ($sessions) ON CONFLICT DO NOTHING""", request.id, request.target)
        jdbc.update("""INSERT INTO trading_deletion_runtime_tasks(request_id,run_id,attempt_token,compute_job_id,deletion_generation,current_deletion_generation)
            SELECT ?,r.id,a.attempt_token,a.compute_job_id,r.deletion_generation,
                GREATEST(r.deletion_generation,s.deletion_generation,b.deletion_generation,acct.deletion_generation,${root.deletionGeneration})
            FROM trading_normalization_attempts a JOIN trading_normalization_runs r ON r.id=a.run_id
            JOIN trading_import_sessions s ON s.id=r.import_session_id JOIN trading_books b ON b.id=s.trading_book_id
            JOIN trading_accounts acct ON acct.id=b.account_id WHERE r.import_session_id IN ($sessions)
            ON CONFLICT (request_id,run_id,attempt_token) DO UPDATE SET compute_job_id=
                COALESCE(trading_deletion_runtime_tasks.compute_job_id,EXCLUDED.compute_job_id)""", request.id, request.target)
        jdbc.update("""INSERT INTO trading_deletion_dispatch_tasks(request_id,dispatch_id,run_id,compute_key)
            SELECT ?,d.id,d.run_id,d.compute_key FROM trading_normalization_dispatches d
            JOIN trading_normalization_runs r ON r.id=d.run_id WHERE r.import_session_id IN ($sessions)
            ON CONFLICT DO NOTHING""", request.id, request.target)
        jdbc.update("""INSERT INTO trading_deletion_revision_tasks(request_id,revision_id)
            SELECT ?,id FROM trading_ledger_revisions WHERE import_session_id IN ($sessions)
            ON CONFLICT DO NOTHING""", request.id, request.target)
        val unfinishedChildren = hasUnfinishedChildren(request)
        val noSessions = jdbc.queryForObject("SELECT NOT EXISTS ($sessions)",
            Boolean::class.java, request.target) == true
        val noRuns = jdbc.queryForObject("""SELECT NOT EXISTS(SELECT 1
            FROM trading_normalization_runs WHERE import_session_id IN ($sessions))""",
            Boolean::class.java, request.target) == true
        // These checks happen before purge. Missing rows after purge are never evidence
        // that an external runtime/spool has vanished.
        if (root.state == DeletionRequestState.CANCELLING_JOBS && root.version > 0 && noRuns && !unfinishedChildren) next = next.verifyAbsent(DeletionPurgeComponent.COMPUTE_RUNTIME, now)
        if (root.state == DeletionRequestState.CANCELLING_JOBS && root.version > 0 && noSessions && !unfinishedChildren) next = next.verifyAbsent(DeletionPurgeComponent.TEMPORARY_COPIES, now)
        // Bounded local cancellation: no dispatch or read grant may restart this scope.
        // Remote cancellation/absence still needs its own task and is never inferred here.
        jdbc.update("""UPDATE trading_normalization_dispatches SET status='OBSOLETE',lease_owner=NULL,
            lease_expires_at=NULL,updated_at=? WHERE run_id IN
            (SELECT id FROM trading_normalization_runs WHERE import_session_id IN ($sessions))
            AND status IN ('PENDING','SUBMITTED','RESULT_READY')""", Timestamp.from(now), request.target)
        jdbc.update("DELETE FROM trading_artifact_read_grants WHERE session_id IN ($sessions)", request.target)
        markArtifactsPending(request)
        jdbc.update("INSERT INTO trading_deletion_worksets(request_id,captured_at) VALUES (?,?)", request.id, Timestamp.from(now))
        return next.recordExecutionProgress(now)
    }

    private fun hasUnfinishedChildren(request: Request): Boolean {
        if (request.type == "TRADING_SOURCE_ARTIFACT") return false
        val relation = when (request.type) {
            "TRADING_ACCOUNT" -> "t.account_id=?"
            "TRADING_BOOK" -> "t.book_id=?"
            "TRADING_IMPORT_SESSION" -> "t.session_id=?"
            else -> error("Unknown deletion scope")
        }
        return jdbc.queryForObject("""SELECT EXISTS(SELECT 1 FROM trading_deletion_requests d
            JOIN trading_deletion_session_tasks t ON t.request_id=d.id
            WHERE d.id<>? AND d.owner_member_id=? AND d.state<>'COMPLETED' AND $relation)""",
            Boolean::class.java, request.id, request.owner, request.target) == true
    }

    private fun purgePrimary(root: DeletionRequest, request: Request, now: java.time.Instant): DeletionRequest {
        var next = root
        val hadRevisions = request.type != "TRADING_SOURCE_ARTIFACT" && jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM trading_ledger_revisions WHERE import_session_id IN (${sessionIds(request)}))",
            Boolean::class.java, request.target) == true
        purgeRows(request)
        if (DeletionPurgeComponent.PRIMARY_DATA in next.checklist.requiredComponents) {
            check(primaryAbsent(request))
            next = next.verifyAbsent(DeletionPurgeComponent.PRIMARY_DATA, now)
            // Published analytics files have no absence-query capability yet (5b).
            if (!hadRevisions && !hasUnfinishedChildren(request)) next = next.verifyAbsent(DeletionPurgeComponent.DERIVED_RESULTS, now)
        }
        return next
    }

    private data class Step(val request: DeletionRequest, val unverified: Boolean)

    private fun DeletionRequest.toRequest() = Request(id.value, owner.value, scope.targetType.name,
        scope.target.value, state.name)

    private fun sessionIds(request: Request): String = "SELECT s.id FROM trading_import_sessions s " +
        "JOIN trading_books b ON b.id=s.trading_book_id WHERE s.owner_member_id=${request.owner} AND " + when (request.type) {
            "TRADING_SOURCE_ARTIFACT" -> "s.id IN (SELECT import_session_id FROM trading_source_artifacts WHERE id=?)"
            "TRADING_IMPORT_SESSION" -> "s.id=?"
            "TRADING_BOOK" -> "b.id=?"
            "TRADING_ACCOUNT" -> "b.account_id=?"
            else -> error("Unknown deletion scope")
        }

    private fun primaryAbsent(request: Request): Boolean {
        val table = when (request.type) {
            "TRADING_IMPORT_SESSION" -> "trading_import_sessions"
            "TRADING_BOOK" -> "trading_books"
            "TRADING_ACCOUNT" -> "trading_accounts"
            else -> error("Raw-only request does not purge primary data")
        }
        return jdbc.queryForObject("SELECT NOT EXISTS(SELECT 1 FROM $table WHERE id=? AND owner_member_id=?)",
            Boolean::class.java, request.target, request.owner) == true
    }

    private fun scopeClause(request: Request): String = "s.owner_member_id=${request.owner} AND " + when (request.type) {
        "TRADING_SOURCE_ARTIFACT" -> "a.id=?"
        "TRADING_IMPORT_SESSION" -> "s.id=?"
        "TRADING_BOOK" -> "b.id=?"
        "TRADING_ACCOUNT" -> "b.account_id=?"
        else -> error("Unknown deletion scope")
    }

    private fun markArtifactsPending(request: Request) {
        jdbc.update("""UPDATE trading_source_artifacts a SET retention_status='DELETION_PENDING', version=a.version+1
            FROM trading_import_sessions s JOIN trading_books b ON b.id=s.trading_book_id
            WHERE a.import_session_id=s.id AND """ + scopeClause(request) +
            " AND a.retention_status IN ('ACTIVE','RETENTION_SCHEDULED')", request.target)
    }

    private fun purgeRows(request: Request) {
        if (request.type == "TRADING_SOURCE_ARTIFACT") {
            jdbc.update("""INSERT INTO trading_deleted_resources (owner_member_id, target_type, target_id, deletion_request_id)
                VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING""", request.owner, request.type, request.target, request.id)
            return
        }
        jdbc.update("""INSERT INTO trading_deleted_resources (owner_member_id, target_type, target_id, deletion_request_id)
            SELECT s.owner_member_id, 'TRADING_IMPORT_SESSION', s.id, ? FROM trading_import_sessions s
            JOIN trading_books b ON b.id=s.trading_book_id WHERE """ + scopeClause(request) +
            " ON CONFLICT DO NOTHING", request.id, request.target)
        purgeNormalizationAndLedger(request)
        when (request.type) {
            "TRADING_IMPORT_SESSION" -> {
                jdbc.update("DELETE FROM trading_source_artifacts WHERE import_session_id=?", request.target)
                jdbc.update("DELETE FROM trading_import_sessions WHERE id=? AND owner_member_id=?", request.target, request.owner)
            }
            "TRADING_BOOK" -> {
                jdbc.update("""DELETE FROM trading_source_artifacts WHERE import_session_id IN
                    (SELECT id FROM trading_import_sessions WHERE trading_book_id=?)""", request.target)
                jdbc.update("DELETE FROM trading_import_sessions WHERE trading_book_id=?", request.target)
                jdbc.update("DELETE FROM trading_books WHERE id=? AND owner_member_id=?", request.target, request.owner)
            }
            "TRADING_ACCOUNT" -> {
                jdbc.update("""INSERT INTO trading_deleted_resources (owner_member_id, target_type, target_id, deletion_request_id)
                    SELECT owner_member_id, 'TRADING_BOOK', id, ? FROM trading_books WHERE account_id=?
                    ON CONFLICT DO NOTHING""", request.id, request.target)
                jdbc.update("""DELETE FROM trading_source_artifacts WHERE import_session_id IN
                    (SELECT s.id FROM trading_import_sessions s JOIN trading_books b ON b.id=s.trading_book_id
                    WHERE b.account_id=?)""", request.target)
                jdbc.update("""DELETE FROM trading_import_sessions WHERE trading_book_id IN
                    (SELECT id FROM trading_books WHERE account_id=?)""", request.target)
                jdbc.update("DELETE FROM trading_books WHERE account_id=?", request.target)
                jdbc.update("DELETE FROM trading_accounts WHERE id=? AND owner_member_id=?", request.target, request.owner)
            }
        }
        jdbc.update("""INSERT INTO trading_deleted_resources (owner_member_id, target_type, target_id, deletion_request_id)
            VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING""", request.owner, request.type, request.target, request.id)
    }

    private fun purgeNormalizationAndLedger(request: Request) {
        enqueueKnownStalePayloads(request)
        // Book/session pointers must be cleared before the immutable revision rows are removed.
        val sessionIds = "SELECT s.id FROM trading_import_sessions s JOIN trading_books b " +
            "ON b.id=s.trading_book_id WHERE " + scopeClause(request)
        val target = request.target
        val revisionIds = "SELECT id FROM trading_ledger_revisions WHERE import_session_id IN ($sessionIds)"
        jdbc.query("SELECT id::text FROM trading_ledger_revisions WHERE import_session_id IN ($sessionIds)",
            { rs, _ -> rs.getString(1) }, target).forEach { analyticsArtifacts?.deleteRevision(it) }
        jdbc.update("UPDATE trading_books SET latest_ledger_revision_id=NULL WHERE latest_ledger_revision_id IN ($revisionIds)", target)
        jdbc.update("UPDATE trading_import_sessions SET created_ledger_revision_id=NULL WHERE id IN ($sessionIds)", target)
        jdbc.update("DELETE FROM trading_ledger_analytics_supplement WHERE revision_id IN ($revisionIds)", target)
        jdbc.update("DELETE FROM trading_ledger_allocations WHERE revision_id IN ($revisionIds)", target)
        jdbc.update("DELETE FROM trading_ledger_revision_records WHERE revision_id IN ($revisionIds)", target)
        jdbc.update("DELETE FROM trading_ledger_review_units WHERE revision_id IN ($revisionIds)", target)
        jdbc.update("DELETE FROM trading_ledger_revisions WHERE import_session_id IN ($sessionIds)", target)
        jdbc.update("""DELETE FROM trading_records r WHERE r.source_evidence_snapshot_id IN (
            SELECT e.snapshot_id FROM trading_source_evidence_snapshots e WHERE e.import_session_id IN ($sessionIds))
            AND NOT EXISTS (SELECT 1 FROM trading_ledger_revision_records m
            WHERE m.trading_book_id=r.trading_book_id AND m.record_id=r.record_id)""", target)
        jdbc.update("DELETE FROM trading_source_evidence_snapshots WHERE import_session_id IN ($sessionIds)", target)
        jdbc.update("""UPDATE trading_books b SET latest_ledger_revision_id=(SELECT r.id
            FROM trading_ledger_revisions r WHERE r.trading_book_id=b.id
            ORDER BY r.revision_number DESC LIMIT 1)
            WHERE b.latest_ledger_revision_id IS NULL AND b.id IN (
                SELECT s.trading_book_id FROM trading_import_sessions s WHERE s.id IN ($sessionIds))""", target)
        val runIds = "SELECT id FROM trading_normalization_runs WHERE import_session_id IN ($sessionIds)"
        // Pending ACK contains only opaque transport identity and must survive primary purge.
        jdbc.update("DELETE FROM trading_normalization_ack_dispatches WHERE run_id IN ($runIds) AND status<>'PENDING'", target)
        jdbc.update("DELETE FROM trading_normalization_result_staging WHERE run_id IN ($runIds)", target)
        jdbc.update("DELETE FROM trading_normalization_audit WHERE run_id IN ($runIds)", target)
        jdbc.update("DELETE FROM trading_normalization_dispatches WHERE run_id IN ($runIds)", target)
        jdbc.update("DELETE FROM trading_normalization_attempts WHERE run_id IN ($runIds)", target)
        jdbc.update("DELETE FROM trading_normalization_runs WHERE import_session_id IN ($sessionIds)", target)
        jdbc.update("DELETE FROM trading_artifact_read_grants WHERE session_id IN ($sessionIds)", target)
    }

    private fun enqueueKnownStalePayloads(request: Request) {
        val now = clock.instant()
        val known = jdbc.query("""SELECT r.id::text,s.id,r.compute_job_id,stage.payload_id,
            stage.transport_sha256,stage.result_hash,stage.attempt_token::text,
            r.deletion_generation,r.retry_policy_json::text,stage.payload_expires_at,
            GREATEST(s.deletion_generation,
                CASE WHEN b.status='DELETION_PENDING' THEN b.deletion_generation ELSE 0 END,
                CASE WHEN a.status='DELETION_PENDING' THEN a.deletion_generation ELSE 0 END)
            FROM trading_normalization_result_staging stage
            JOIN trading_normalization_runs r ON r.id=stage.run_id
            JOIN trading_import_sessions s ON s.id=r.import_session_id
            JOIN trading_books b ON b.id=s.trading_book_id
            JOIN trading_accounts a ON a.id=b.account_id
            WHERE """ + scopeClause(request) + """ AND r.status='RESULT_READY'
            AND r.compute_job_id=stage.compute_job_id AND r.attempt_token=stage.attempt_token
            AND r.deletion_generation=stage.deletion_generation
            AND stage.payload_expires_at>?""", { rs, _ ->
            KnownPayload(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getLong(8),
                rs.getString(9), rs.getTimestamp(10).toInstant(), rs.getLong(11))
        }, request.target, Timestamp.from(now))
        for (payload in known) {
            if (payload.currentGeneration <= payload.deletionGeneration) continue
            val key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                "${payload.runId}:${payload.jobId}:${payload.payloadId}:DISCARDED_STALE"
                    .toByteArray(Charsets.UTF_8)))
            jdbc.update("""INSERT INTO trading_normalization_ack_dispatches
                (id,run_id,core_import_session_id,compute_job_id,payload_id,payload_sha256,
                result_hash,attempt_token,deletion_generation,current_deletion_generation,
                disposition,idempotency_key,retry_policy_json,payload_expires_at,status,
                next_attempt_at,created_at,updated_at)
                VALUES (?::uuid,?::uuid,?,?,?,?,?,?::uuid,?,?,'DISCARDED_STALE',?,?::jsonb,?,
                'PENDING',?,?,?) ON CONFLICT (idempotency_key) DO NOTHING""",
                UUID.randomUUID().toString(), payload.runId, payload.sessionId, payload.jobId,
                payload.payloadId, payload.payloadSha256, payload.resultHash, payload.attemptToken,
                payload.deletionGeneration, payload.currentGeneration, key,
                payload.retryPolicyJson, Timestamp.from(payload.expiresAt), Timestamp.from(now),
                Timestamp.from(now), Timestamp.from(now))
        }
    }

    private data class KnownPayload(val runId: String, val sessionId: Long, val jobId: String,
        val payloadId: String, val payloadSha256: String, val resultHash: String,
        val attemptToken: String, val deletionGeneration: Long, val retryPolicyJson: String,
        val expiresAt: java.time.Instant, val currentGeneration: Long)

    private fun recordFailure(expected: DeletionRequest) {
        transactions.execute {
            val failed = expected.recordFailure(clock.instant())
            // A competing completion/retry invalidates both failure and artifact changes.
            if (!execution.commit(expected, failed)) return@execute
            if (failed.state == DeletionRequestState.FAILED) {
                val request = failed.toRequest()
                jdbc.update("""UPDATE trading_source_artifacts a SET retention_status='DELETION_FAILED',
                    deletion_failure='ARTIFACT_DELETION_FAILED', version=a.version+1
                    FROM trading_import_sessions s JOIN trading_books b ON b.id=s.trading_book_id
                    WHERE a.import_session_id=s.id AND """ + scopeClause(request) +
                    " AND a.retention_status='DELETION_PENDING'", request.target)
            }
        }
    }

    private data class Request(val id: Long, val owner: Long, val type: String, val target: Long, val state: String)
}
