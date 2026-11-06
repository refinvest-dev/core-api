package com.refinvest.core.tradingreview.domain.importsession

import com.refinvest.core.tradingreview.domain.*
import com.refinvest.core.tradingreview.domain.valueobject.*

data class NormalizationInput(
    val tradingBookId: TradingBookId,
    val importSessionId: TradingImportSessionId,
    val ownerId: Long,
    val artifacts: List<NormalizationArtifactInput>,
    val venue: String,
    val productFamily: String,
    val positionMode: String,
    val settlementAsset: String,
    val sourceTimezone: String,
    val reviewTimezone: String,
    val versionSetJson: String,
    val instrumentTermsJson: String,
    val dataPolicyVersion: String,
    val retryPolicyVersion: String,
    val retryPolicyJson: String,
    val deletionGeneration: Long,
) {
    init {
        require(deletionGeneration >= 0 && ownerId > 0)
        require(artifacts.size in 0..2 && artifacts.map { it.role }.distinct().size == artifacts.size &&
            artifacts.map { it.artifactId }.distinct().size == artifacts.size)

    }
}
