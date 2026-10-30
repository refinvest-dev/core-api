package com.refinvest.core.tradingreview.domain.valueobject

/** Versioned bounded metadata only. Collection members are represented by result digests/counts. */
data class LedgerRevisionMetadata(
    val manifestBaseJson: String,
    val coverageJson: String,
    val capabilityJson: String,
    val qualityBaseJson: String,
) {
    init { require(listOf(manifestBaseJson, coverageJson, capabilityJson, qualityBaseJson).all { it.isNotBlank() }) }
}
