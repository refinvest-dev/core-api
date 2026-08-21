package com.refinvest.core.strategy.port.outbound

fun interface StrategyVersionBacktestReader {
    fun findById(strategyVersionId: Long): StrategyVersionBacktestReadModel?
}
