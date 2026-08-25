package com.refinvest.core.backtest.port.inbound.list

import com.refinvest.core.backtest.domain.valueobject.StrategyId

data class ListBacktestRunsQuery(
    val strategyId: StrategyId,
    val page: Int = 0,
    val size: Int = 20,
) {
    init {
        require(page >= 0) { "Page must not be negative" }
        require(size in 1..100) { "Size must be between 1 and 100" }
    }
}
