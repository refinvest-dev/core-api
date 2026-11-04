package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.tradingreview.domain.DeletionRequest
import com.refinvest.core.tradingreview.domain.valueobject.*
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.time.Clock
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

/** Scope selection, primary-row purge and stale payload outbox remain inside the caller transaction. */
internal class DeletionScopeRows(private val jdbc: JdbcTemplate, private val clock: Clock) {
    fun hasUnfinishedChildren(root: DeletionRequest) = hasUnfinishedChildren(root.toRequest())
    fun sessionIds(root: DeletionRequest) = sessionIds(root.toRequest())
    fun primaryAbsent(root: DeletionRequest) = primaryAbsent(root.toRequest())
    fun scopeClause(root: DeletionRequest) = scopeClause(root.toRequest())
    fun markArtifactsPending(root: DeletionRequest) = markArtifactsPending(root.toRequest())
    fun purgeRows(root: DeletionRequest) = purgeRows(root.toRequest())

    private fun hasUnfinishedChildren(request: Request): Boolean {
        if (request.type == "TRADING_SOURCE_ARTIFACT") return false
        val relation = when (request.type) {
            "TRADING_ACCOUNT" -> "t.account_id=?"
            "TRADING_BOOK" -> "t.book_id=?"
            "TRADING_IMPORT_SESSION" -> "t.session_id=?"
            else -> error("Unknown deletion scope")
        }
        val descendants = when (request.type) {
            "TRADING_ACCOUNT" -> "'TRADING_BOOK','TRADING_IMPORT_SESSION','TRADING_SOURCE_ARTIFACT'"
            "TRADING_BOOK" -> "'TRADING_IMPORT_SESSION','TRADING_SOURCE_ARTIFACT'"
            else -> "'TRADING_SOURCE_ARTIFACT'"
        }
        return jdbc.queryForObject("""WITH RECURSIVE repaired AS (
            SELECT d.id,d.remediation_of,d.owner_member_id,d.target_type,d.target_id,d.deletion_generation
            FROM trading_deletion_requests d WHERE d.state='COMPLETED' AND
                (SELECT count(*) FROM trading_deletion_checkpoints c WHERE c.request_id=d.id) =
                    CASE WHEN d.target_type='TRADING_SOURCE_ARTIFACT' THEN 4 ELSE 6 END
            UNION ALL
            SELECT d.id,d.remediation_of,d.owner_member_id,d.target_type,d.target_id,d.deletion_generation
            FROM trading_deletion_requests d JOIN repaired r ON r.remediation_of=d.id
                AND r.owner_member_id=d.owner_member_id AND r.target_type=d.target_type
                AND r.target_id=d.target_id AND r.deletion_generation=d.deletion_generation)
            SELECT EXISTS(SELECT 1 FROM trading_deletion_requests d
            JOIN trading_deletion_session_tasks t ON t.request_id=d.id
            WHERE d.id<>? AND d.owner_member_id=? AND d.target_type IN ($descendants)
                AND NOT EXISTS (SELECT 1 FROM repaired r WHERE r.id=d.id) AND $relation)""",
            Boolean::class.java, request.id, request.owner, request.target) == true
    }

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

    private fun DeletionRequest.toRequest() = Request(id.value, owner.value, scope.targetType.name,
        scope.target.value, state.name)
    private data class Request(val id: Long, val owner: Long, val type: String, val target: Long, val state: String)}
