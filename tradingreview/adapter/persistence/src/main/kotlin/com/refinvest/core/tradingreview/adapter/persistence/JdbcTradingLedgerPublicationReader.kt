package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportSessionId
import com.refinvest.core.tradingreview.port.TradingLedgerPublicationReader
import com.refinvest.core.tradingreview.port.PublishedLedgerSummary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class JdbcTradingLedgerPublicationReader(private val jdbc: JdbcTemplate) : TradingLedgerPublicationReader {
    override fun publishedForSession(sessionId: TradingImportSessionId,
                                     owner: MemberId): PublishedLedgerSummary? = jdbc.query("""
        SELECT r.id::text,r.quality_report_json::text,r.capability_json::text,r.published_at
        FROM trading_ledger_revisions r JOIN trading_import_sessions s ON s.id=r.import_session_id
        JOIN trading_books b ON b.id=s.trading_book_id
        JOIN trading_accounts a ON a.id=b.account_id
        WHERE r.import_session_id=? AND s.owner_member_id=? AND s.status='ACCEPTED'
        AND NOT s.deletion_pending AND b.status='ACTIVE' AND a.status='ACTIVE'
        ORDER BY r.published_at DESC LIMIT 1""", { rs, _ ->
        PublishedLedgerSummary(rs.getString(1), rs.getString(2), rs.getString(3),
            rs.getTimestamp(4).toInstant())
    }, sessionId.value, owner.value).firstOrNull()
}
