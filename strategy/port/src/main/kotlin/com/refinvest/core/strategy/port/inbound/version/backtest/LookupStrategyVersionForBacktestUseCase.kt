package com.refinvest.core.strategy.port.inbound.version.backtest

fun interface LookupStrategyVersionForBacktestUseCase {
    fun execute(query: LookupStrategyVersionForBacktestQuery): LookupStrategyVersionForBacktestResult?
}
