package com.refinvest.core.tradingreview.port.outbound.persistence.ledger

import java.time.Instant

data class PublishedLedgerSummary(
    val revisionId: String,
    val qualityReportJson: String,
    val capabilityJson: String,
    val publishedAt: Instant,
)
