package com.refinvest.core.backtest.port.inbound.list

import com.refinvest.core.backtest.domain.valueobject.StrategyVersionId

fun interface GetLatestTerminalBacktestRunUseCase {
    /** Returns the latest COMPLETED or FAILED run for the owned strategy version. */
    fun execute(strategyVersionId: StrategyVersionId): BacktestRunSummary?
}
