package com.refinvest.core.backtest.port.inbound.backtest.poll

fun interface PollBacktestStatusUseCase {
    fun execute(query: PollBacktestStatusQuery): PollBacktestStatusResult?
}
