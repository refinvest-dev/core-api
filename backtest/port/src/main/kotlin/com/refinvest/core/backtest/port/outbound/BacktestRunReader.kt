package com.refinvest.core.backtest.port.outbound

import com.refinvest.core.backtest.domain.valueobject.BacktestRunId
import com.refinvest.core.backtest.domain.valueobject.StrategyId

fun interface BacktestRunReader {
    fun findById(id: BacktestRunId): BacktestRunReadModel?

    fun findByStrategyId(
        strategyId: StrategyId,
        page: Int,
        size: Int,
    ): BacktestRunPageReadModel = BacktestRunPageReadModel(emptyList(), 0)
}
