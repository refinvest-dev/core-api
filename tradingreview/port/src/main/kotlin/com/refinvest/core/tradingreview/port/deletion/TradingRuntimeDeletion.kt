package com.refinvest.core.tradingreview.port.deletion

import com.refinvest.core.tradingreview.domain.valueobject.*
import java.time.Duration
import java.time.Instant

/** Run input generation and deletion-scope generation are independent counters. */
data class TradingRuntimeDeletion(
    val runId: NormalizationRunId,
    val sessionId: TradingImportSessionId,
    val sourceGeneration: Long,
    val requestId: DeletionRequestId,
    val deletionGeneration: Long,
) {
    init { require(sourceGeneration >= 0 && deletionGeneration > 0) }
}

sealed interface TradingRuntimeDeletionOutcome {
    data class Pending(val retryAfter: Duration) : TradingRuntimeDeletionOutcome
    data class Absent(val verifiedAt: Instant) : TradingRuntimeDeletionOutcome
}

interface TradingRuntimeDeletionClient {
    fun purge(command: TradingRuntimeDeletion): TradingRuntimeDeletionOutcome
}

/** Unattributed pre-upgrade spool bytes must drain before temporary-copy absence is claimed. */
interface LegacyTradingTemporaryCopies {
    fun absent(): Boolean
}
