package com.refinvest.core.tradingreview.port

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportSessionId
import java.time.Instant

/** Only committed revisions are returned; staging has no read path. */
interface TradingLedgerPublicationReader {
    fun publishedForSession(sessionId: TradingImportSessionId, owner: MemberId): PublishedLedgerSummary?
}

data class PublishedLedgerSummary(
    val revisionId: String,
    val qualityReportJson: String,
    val capabilityJson: String,
    val publishedAt: Instant,
)
