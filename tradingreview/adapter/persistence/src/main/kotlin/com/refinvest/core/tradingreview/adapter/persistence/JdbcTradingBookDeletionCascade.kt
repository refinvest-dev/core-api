package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.tradingreview.port.book.TradingAccountBookDeletion
import com.refinvest.core.tradingreview.port.book.TradingBookDeletionCascade
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp

@Repository
class JdbcTradingBookDeletionCascade(private val jdbc: JdbcTemplate) : TradingBookDeletionCascade {
    override fun markDeleting(deletion: TradingAccountBookDeletion): Long? {
        check(jdbc.execute(ConnectionCallback { !it.autoCommit }) == true) { "Account cascade requires an enclosing transaction" }
        val current = jdbc.query("""SELECT id FROM trading_accounts WHERE id=? AND owner_member_id=?
            AND status='DELETION_PENDING' AND deletion_generation=? AND version=? AND updated_at=? FOR UPDATE""",
            { rs, _ -> rs.getLong(1) }, deletion.accountId.value, deletion.owner.value,
            deletion.accountDeletionGeneration, deletion.expectedAccountVersion, Timestamp.from(deletion.requestedAt)).firstOrNull()
            ?: return null
        val total = jdbc.queryForObject("SELECT count(*) FROM trading_books WHERE account_id=? AND owner_member_id=?",
            Long::class.java, current, deletion.owner.value) ?: 0L
        jdbc.update("""UPDATE trading_books SET status='DELETION_PENDING', deletion_generation=deletion_generation+1,
            updated_at=GREATEST(updated_at,?),version=version+1 WHERE account_id=? AND owner_member_id=? AND status IN ('ACTIVE','ARCHIVED')""",
            Timestamp.from(deletion.requestedAt), current, deletion.owner.value)
        return total
    }
}
