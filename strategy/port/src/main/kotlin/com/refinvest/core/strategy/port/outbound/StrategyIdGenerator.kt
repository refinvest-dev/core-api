package com.refinvest.core.strategy.port.outbound

import com.refinvest.core.strategy.domain.StrategyId
import com.refinvest.core.strategy.domain.StrategyVersionId

fun interface StrategyIdGenerator {
    fun next(): StrategyId
}

fun interface StrategyVersionIdGenerator {
    fun next(): StrategyVersionId
}
