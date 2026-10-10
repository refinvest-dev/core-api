package com.refinvest.core.tradingreview.adapter.persistence

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Clock

@Component
@ConditionalOnProperty(prefix = "refinvest.trading-review", name = ["deletion-enabled"], havingValue = "true", matchIfMissing = true)
class TradingDeletionWorker(
    private val jdbc: JdbcTemplate,
    private val transactions: TransactionTemplate,
    private val clock: Clock,
) {
    @Scheduled(fixedDelayString = "\${refinvest.trading-review.deletion-delay:5000}")
    fun purgePending() {
        repeat(20) {
            var attemptedId: Long? = null
            val worked = try { transactions.execute {
                val request = jdbc.query("""SELECT id, owner_member_id, target_type, target_id
                    FROM trading_deletion_requests WHERE state='REQUESTED' ORDER BY requested_at, id
                    LIMIT 1 FOR UPDATE SKIP LOCKED""", { rs, _ ->
                    Request(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4))
                }).firstOrNull() ?: return@execute false
                attemptedId = request.id
                val now = Timestamp.from(clock.instant())
                jdbc.update("UPDATE trading_deletion_requests SET state='CANCELLING_JOBS', updated_at=? WHERE id=?", now, request.id)
                jdbc.update("UPDATE trading_deletion_requests SET state='PURGING_PRIMARY', updated_at=? WHERE id=?", now, request.id)
                if (request.type == "TRADING_ACCOUNT") {
                    jdbc.update("""INSERT INTO trading_deleted_resources (owner_member_id, target_type, target_id, deletion_request_id)
                        SELECT owner_member_id, 'TRADING_BOOK', id, ? FROM trading_books WHERE account_id=?
                        ON CONFLICT DO NOTHING""", request.id, request.target)
                    jdbc.update("DELETE FROM trading_books WHERE account_id=?", request.target)
                    jdbc.update("DELETE FROM trading_accounts WHERE id=? AND owner_member_id=?", request.target, request.owner)
                } else {
                    jdbc.update("DELETE FROM trading_books WHERE id=? AND owner_member_id=?", request.target, request.owner)
                }
                jdbc.update("""INSERT INTO trading_deleted_resources (owner_member_id, target_type, target_id, deletion_request_id)
                    VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING""", request.owner, request.type, request.target, request.id)
                jdbc.update("UPDATE trading_deletion_requests SET state='PURGING_OBJECTS', updated_at=? WHERE id=?", now, request.id)
                jdbc.update("""UPDATE trading_deletion_requests SET state='COMPLETED', updated_at=?, completed_at=?
                    WHERE id=?""", now, now, request.id)
                true
            } == true } catch (failure: RuntimeException) {
                attemptedId?.let { id ->
                    transactions.execute {
                        jdbc.update("""UPDATE trading_deletion_requests SET attempt_count=attempt_count+1,
                            state=CASE WHEN attempt_count >= 4 THEN 'FAILED' ELSE 'REQUESTED' END,
                            failure_code='DERIVED_DATA_DELETION_FAILED', updated_at=?
                            WHERE id=? AND state='REQUESTED'""", Timestamp.from(clock.instant()), id)
                    }
                }
                return
            }
            if (!worked) return
        }
    }

    private data class Request(val id: Long, val owner: Long, val type: String, val target: Long)
}
