package com.refinvest.core.backtest.port.inbound.backtest.usage

fun interface GetMonthlyBacktestUsageUseCase {
    fun execute(query: GetMonthlyBacktestUsageQuery): GetMonthlyBacktestUsageResult
}
