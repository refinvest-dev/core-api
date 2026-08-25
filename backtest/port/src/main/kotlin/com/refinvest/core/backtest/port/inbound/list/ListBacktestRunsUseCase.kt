package com.refinvest.core.backtest.port.inbound.list

fun interface ListBacktestRunsUseCase {
    fun execute(query: ListBacktestRunsQuery): ListBacktestRunsResult
}
