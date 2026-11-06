package com.refinvest.core.tradingreview.domain.importsession

import com.refinvest.core.tradingreview.domain.*
import com.refinvest.core.tradingreview.domain.valueobject.*

data class NormalizationArtifactInput(
    val artifactId: TradingSourceArtifactId,
    val role: TradingArtifactRole,
    val sha256: String,
    val contentLength: Long,
    val mediaType: String,
    val dialect: String,
) {
    init {
        require(Regex("[0-9a-f]{64}").matches(sha256) && contentLength in 1..52_428_800)
        require(mediaType.isNotBlank() && dialect.isNotBlank())
    }
}
