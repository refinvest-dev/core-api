package com.refinvest.core.backtest.port.outbound.compute

import com.refinvest.core.backtest.domain.valueobject.DatasetSnapshotId
import com.refinvest.core.backtest.domain.valueobject.FeeModel
import com.refinvest.core.backtest.domain.valueobject.Period
import com.refinvest.core.backtest.domain.valueobject.StrategyVersionId
import java.time.Duration
import java.util.UUID

/**
 * Port for Compute's asynchronous backtest API.
 *
 * TODO: a later orchestration step must obtain this complete payload through strategy application
 * before invoking this port. RunBacktest currently creates only a PENDING BacktestRun.
 */
fun interface ComputeClient {
    fun requestBacktest(request: ComputeBacktestRequest): ComputeBacktestSubmission
}

data class ComputeBacktestRequest(
    val idempotencyKey: ComputeIdempotencyKey,
    val strategyVersionId: StrategyVersionId,
    val strategyVersion: StrategyVersionPayload,
    val feeModel: FeeModel,
    val period: Period,
    val datasetSnapshotId: DatasetSnapshotId? = null,
)

@JvmInline
value class ComputeIdempotencyKey(val value: UUID)

fun interface ComputeIdempotencyKeyGenerator {
    fun next(): ComputeIdempotencyKey
}

sealed interface ComputeBacktestSubmission {
    data class Accepted(val computeRunId: String) : ComputeBacktestSubmission
    data class RetryLater(val retryAfter: Duration) : ComputeBacktestSubmission
    data class Rejected(val reason: String, val errorCode: String? = null) : ComputeBacktestSubmission
}

/** Mirrors compute-api's StrategyVersionPayload without coupling to strategy domain. */
data class StrategyVersionPayload(
    val primarySignalAsset: String,
    val conditions: List<ComputeConditionPayload>,
    val executionAsset: String,
    val lag: Int,
    val exit: TimeBasedExitPayload,
    val positionPolicy: PositionPolicyPayload,
)

data class ComputeConditionPayload(
    val operator: String,
    val logicalCombinator: String?,
    val operandA: MetricReferencePayload,
    val operandB: ComputeConditionOperandPayload,
)

sealed interface ComputeConditionOperandPayload

data class MetricOperandPayload(val value: MetricReferencePayload) : ComputeConditionOperandPayload

data class LiteralOperandPayload(val value: Double) : ComputeConditionOperandPayload

data class MetricReferencePayload(
    val asset: String,
    val metric: String,
    val window: Int?,
)

data class TimeBasedExitPayload(val holdingSignalSessions: Int)

data class PositionPolicyPayload(
    val longOnly: Boolean,
    val singlePosition: Boolean,
    val duplicateEntry: String,
)
