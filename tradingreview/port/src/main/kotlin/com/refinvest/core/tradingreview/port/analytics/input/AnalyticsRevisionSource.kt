package com.refinvest.core.tradingreview.port.analytics.input

data class AnalyticsRevisionSource(
    val revisionId: String,
    val resultSchemaVersion: String,
    val ledgerContentHash: String,
    val manifestHash: String,
    val canonicalSchemaVersion: String,
    val reviewUnitSchemaVersion: String,
    val instrumentTermsJson: String,
    val manifestJson: String,
    val capabilityJson: String,
    val canonicalRecordCount: Long,
    val reviewUnitCount: Long,
)
