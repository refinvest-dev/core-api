package com.refinvest.core.backtest.port.inbound.backtest.list

fun interface ListBacktestRunsUseCase {
    fun execute(query: ListBacktestRunsQuery): ListBacktestRunsResult
}
