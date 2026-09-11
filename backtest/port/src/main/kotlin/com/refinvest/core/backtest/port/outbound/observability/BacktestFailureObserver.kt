package com.refinvest.core.backtest.port.outbound.observability

import com.refinvest.core.backtest.domain.valueobject.BacktestRunId
import com.refinvest.core.backtest.domain.valueobject.StrategyId
import com.refinvest.core.backtest.domain.valueobject.StrategyVersionId

/** Records an already-persisted terminal failure without exposing failure payloads to telemetry. */
fun interface BacktestFailureObserver {
    fun recordComputeFailure(observation: ComputeBacktestFailureObservation)
}

data class ComputeBacktestFailureObservation(
    val backtestRunId: BacktestRunId,
    val strategyId: StrategyId,
    val strategyVersionId: StrategyVersionId,
    val errorCode: String?,
)
