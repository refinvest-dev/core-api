package com.refinvest.core.strategy.port.outbound.id

import com.refinvest.core.strategy.domain.valueobject.StrategyId

fun interface StrategyIdGenerator {
    fun next(): StrategyId
}
