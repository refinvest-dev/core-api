package com.refinvest.core.strategy.port.inbound.strategy.version.backtest

fun interface LookupStrategyVersionForBacktestUseCase {
    fun execute(query: LookupStrategyVersionForBacktestQuery): LookupStrategyVersionForBacktestResult?
}
