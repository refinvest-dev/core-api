package com.refinvest.core.backtest.port.inbound.get

fun interface GetBacktestResultUseCase {
    fun execute(query: GetBacktestResultQuery): GetBacktestResultResult?
}
