package com.refinvest.core.backtest.port.inbound.backtest.get

fun interface GetBacktestResultUseCase {
    fun execute(query: GetBacktestResultQuery): GetBacktestResultResult?
}
