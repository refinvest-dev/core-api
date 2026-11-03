package com.refinvest.core.tradingreview.port.inbound.importsession

import java.time.Instant

data class PublishedLedgerResult(
    val revisionId: String,
    val qualityReportJson: String,
    val capabilityJson: String,
    val publishedAt: Instant,
)
