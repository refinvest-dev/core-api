package com.refinvest.core.backtest.port.inbound.usage

fun interface GetMonthlyBacktestUsageUseCase {
    fun execute(query: GetMonthlyBacktestUsageQuery): GetMonthlyBacktestUsageResult
}
