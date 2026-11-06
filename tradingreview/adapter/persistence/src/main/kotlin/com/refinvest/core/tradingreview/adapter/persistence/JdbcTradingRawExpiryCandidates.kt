package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.tradingreview.port.outbound.persistence.importsession.TradingRawExpiryCandidate
import com.refinvest.core.tradingreview.port.outbound.persistence.importsession.TradingRawExpiryCandidates

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.valueobject.*
import java.sql.Timestamp
import java.time.Instant
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class JdbcTradingRawExpiryCandidates(private val jdbc: JdbcTemplate) : TradingRawExpiryCandidates {
    override fun due(now: Instant, limit: Int): List<TradingRawExpiryCandidate> {
        require(limit in 1..100)
        return jdbc.query("""SELECT s.id,s.owner_member_id,s.trading_book_id,b.account_id
            FROM trading_import_sessions s JOIN trading_books b ON b.id=s.trading_book_id
            JOIN trading_accounts a ON a.id=b.account_id JOIN trading_source_artifacts r ON r.import_session_id=s.id
            WHERE s.status IN ('RECEIVED','VALIDATING','PROCESSING_FAILED') AND NOT s.deletion_pending
            AND s.raw_expired_at IS NULL AND s.failure_code IS DISTINCT FROM 'SOURCE_ARTIFACT_DELETED'
            AND b.status='ACTIVE' AND a.status='ACTIVE' AND r.raw_expires_at<=?
            GROUP BY s.id,b.account_id ORDER BY min(r.raw_expires_at),s.id LIMIT ?""", { rs, _ ->
            TradingRawExpiryCandidate(TradingImportSessionId(rs.getLong(1)), MemberId(rs.getLong(2)),
                TradingBookId(rs.getLong(3)), TradingAccountId(rs.getLong(4)))
        }, Timestamp.from(now), limit)
    }
}
