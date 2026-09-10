package com.refinvest.core.backtest.port.inbound.list

import com.refinvest.core.backtest.domain.valueobject.StrategyVersionId

fun interface GetLatestCompletedBacktestRunUseCase {
    fun execute(strategyVersionId: StrategyVersionId): BacktestRunSummary?
}
