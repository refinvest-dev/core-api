package com.refinvest.core.backtest.port.inbound.backtest.run

fun interface RunBacktestUseCase {
    fun execute(command: RunBacktestCommand): RunBacktestResult
}
