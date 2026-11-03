package com.refinvest.core.tradingreview.port.ledger

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportSessionId
import java.time.Instant

data class PublishedLedgerSummary(
    val revisionId: String,
    val qualityReportJson: String,
    val capabilityJson: String,
    val publishedAt: Instant,
)
