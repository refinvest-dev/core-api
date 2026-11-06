package com.refinvest.core.tradingreview.port.outbound.artifactgrant

import com.refinvest.core.tradingreview.domain.importsession.NormalizationArtifactInput
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportSessionId

data class ArtifactGrantBinding(
    val artifact: NormalizationArtifactInput, val logicalJobId: String,
    val sessionId: TradingImportSessionId, val attemptToken: String, val deletionGeneration: Long,
)
