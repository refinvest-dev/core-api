package com.refinvest.core.strategy.port.outbound.persistence.version

fun interface StrategyVersionBacktestReader {
    fun findById(strategyVersionId: Long): StrategyVersionBacktestReadModel?
}
