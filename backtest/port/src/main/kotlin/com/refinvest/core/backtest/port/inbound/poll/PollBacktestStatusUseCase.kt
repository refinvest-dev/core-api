package com.refinvest.core.backtest.port.inbound.poll

fun interface PollBacktestStatusUseCase {
    fun execute(query: PollBacktestStatusQuery): PollBacktestStatusResult?
}
