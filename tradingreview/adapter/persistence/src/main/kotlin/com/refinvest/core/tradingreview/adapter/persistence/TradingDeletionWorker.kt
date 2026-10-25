package com.refinvest.core.tradingreview.adapter.persistence

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
) {
    @Scheduled(fixedDelay = 5000)
    fun purgePending() {
        repeat(20) {
            var attemptedId: Long? = null
            val worked = try {
                transactions.execute {
                    val request = jdbc.query("""SELECT id, owner_member_id, target_type, target_id, state
                        FROM trading_deletion_requests WHERE state IN ('REQUESTED','PURGING_OBJECTS')
                        ORDER BY requested_at, id LIMIT 1 FOR UPDATE SKIP LOCKED""", { rs, _ ->
                        Request(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getString(5))
                    }).firstOrNull() ?: return@execute false
                    attemptedId = request.id
                    val now = Timestamp.from(clock.instant())
                    if (request.state == "REQUESTED") {
                        jdbc.update("UPDATE trading_deletion_requests SET state='CANCELLING_JOBS', updated_at=? WHERE id=?", now, request.id)
                        jdbc.update("UPDATE trading_deletion_requests SET state='PURGING_PRIMARY', updated_at=? WHERE id=?", now, request.id)
                        markArtifactsPending(request)
                        jdbc.update("UPDATE trading_deletion_requests SET state='PURGING_OBJECTS', updated_at=? WHERE id=?", now, request.id)
                    }
                    val artifacts = jdbc.query("""SELECT a.id, a.object_key FROM trading_source_artifacts a
                        JOIN trading_import_sessions s ON s.id=a.import_session_id
                        JOIN trading_books b ON b.id=s.trading_book_id WHERE """ + scopeClause(request) +
                        " AND a.retention_status='DELETION_PENDING' ORDER BY a.id LIMIT 20 FOR UPDATE OF a SKIP LOCKED",
                        { rs, _ -> rs.getLong(1) to rs.getString(2) }, request.target)
                    for ((id, key) in artifacts) {
                        storage.delete(key)
                        if (storage.exists(key)) throw IllegalStateException("Object deletion incomplete")
                        jdbc.update("""UPDATE trading_source_artifacts SET retention_status='DELETED',
                            object_key=NULL, wrapped_dek=NULL, key_version=NULL, filename=NULL,
                            deleted_at=?, deletion_failure=NULL, version=version+1 WHERE id=?""", now, id)
                    }
                    val remaining = jdbc.queryForObject("""SELECT EXISTS(SELECT 1 FROM trading_source_artifacts a
                        JOIN trading_import_sessions s ON s.id=a.import_session_id
                        JOIN trading_books b ON b.id=s.trading_book_id WHERE """ + scopeClause(request) +
                        " AND a.retention_status<>'DELETED')", Boolean::class.java, request.target) == true
                    if (!remaining) {
                        purgeRows(request)
                        jdbc.update("""UPDATE trading_deletion_requests SET state='COMPLETED', updated_at=?, completed_at=?,
                            failure_code=NULL WHERE id=?""", now, now, request.id)
                    }
                    true
                } == true
            } catch (_: Exception) {
                attemptedId?.let(::recordFailure)
                return
            }
            if (!worked) return
        }
    }

    private fun scopeClause(request: Request): String = when (request.type) {
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

    private fun recordFailure(id: Long) {
        transactions.execute {
            val now = Timestamp.from(clock.instant())
            jdbc.update("""UPDATE trading_deletion_requests SET attempt_count=attempt_count+1,
                state=CASE WHEN attempt_count >= 4 THEN 'FAILED' ELSE state END,
                failure_code='DELETION_PARTIALLY_COMPLETED', updated_at=?
                WHERE id=? AND state IN ('REQUESTED','PURGING_OBJECTS')""", now, id)
            val terminal = jdbc.queryForObject("SELECT state='FAILED' FROM trading_deletion_requests WHERE id=?",
                Boolean::class.java, id) == true
            if (terminal) {
                val request = jdbc.query("SELECT target_type, target_id FROM trading_deletion_requests WHERE id=?",
                    { rs, _ -> Request(id, 0, rs.getString(1), rs.getLong(2), "FAILED") }, id).single()
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
