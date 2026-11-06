package com.refinvest.core.tradingreview.port.outbound.compute.deletion

import com.refinvest.core.tradingreview.domain.valueobject.*
import java.time.Duration
import java.time.Instant

data class TradingRuntimeDeletion(
    val runId: NormalizationRunId,
    val sessionId: TradingImportSessionId,
    val sourceGeneration: Long,
    val requestId: DeletionRequestId,
    val deletionGeneration: Long,
) {
    init { require(sourceGeneration >= 0 && deletionGeneration > 0) }
}
