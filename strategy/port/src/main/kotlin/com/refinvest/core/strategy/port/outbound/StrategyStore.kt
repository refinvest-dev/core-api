package com.refinvest.core.strategy.port.outbound

import com.refinvest.core.strategy.domain.Strategy

fun interface StrategyStore {
    fun save(strategy: Strategy)
}
