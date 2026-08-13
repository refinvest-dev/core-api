package com.refinvest.core.backtest.port.outbound

import com.refinvest.core.backtest.domain.DatasetSnapshotId
import com.refinvest.core.backtest.domain.FeeModel
import com.refinvest.core.backtest.domain.Period
import com.refinvest.core.backtest.domain.StrategyVersionId

/**
 * Port for Compute's asynchronous backtest API.
 *
 * TODO: a later orchestration step must obtain this complete payload through strategy application
 * before invoking this port. RunBacktest currently creates only a PENDING BacktestRun.
 */
fun interface ComputeClient {
    fun requestBacktest(request: ComputeBacktestRequest): ComputeBacktestAccepted
}

data class ComputeBacktestRequest(
    val strategyVersionId: StrategyVersionId,
    val strategyVersion: StrategyVersionPayload,
    val feeModel: FeeModel,
    val period: Period,
    val datasetSnapshotId: DatasetSnapshotId? = null,
)

data class ComputeBacktestAccepted(
    val runId: String,
)

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
