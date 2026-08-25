package com.refinvest.core.backtest.port.inbound.run

fun interface RunBacktestUseCase {
    fun execute(command: RunBacktestCommand): RunBacktestResult
}
