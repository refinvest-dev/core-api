package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.tradingreview.port.RawArtifactStorage
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Clock

@Component
@ConditionalOnProperty(prefix = "refinvest.trading-review", name = ["deletion-enabled"], havingValue = "true", matchIfMissing = true)
class TradingDeletionWorker(
    private val jdbc: JdbcTemplate,
    private val transactions: TransactionTemplate,
    private val storage: RawArtifactStorage,
    private val clock: Clock,
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
