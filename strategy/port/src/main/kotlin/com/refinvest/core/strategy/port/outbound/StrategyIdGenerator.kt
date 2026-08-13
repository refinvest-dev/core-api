package com.refinvest.core.strategy.port.outbound

import com.refinvest.core.strategy.domain.StrategyId

fun interface StrategyIdGenerator {
    fun next(): StrategyId
}
