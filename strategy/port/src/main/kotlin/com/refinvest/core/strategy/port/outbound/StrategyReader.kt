package com.refinvest.core.strategy.port.outbound

import com.refinvest.core.strategy.domain.valueobject.StrategyId
fun interface StrategyReader {
    fun findById(id: StrategyId): StrategyReadModel?
}
