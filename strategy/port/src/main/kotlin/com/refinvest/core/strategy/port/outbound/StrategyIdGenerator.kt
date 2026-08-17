package com.refinvest.core.strategy.port.outbound

import com.refinvest.core.strategy.domain.valueobject.StrategyId
import com.refinvest.core.strategy.domain.valueobject.StrategyVersionId

fun interface StrategyIdGenerator {
    fun next(): StrategyId
}

fun interface StrategyVersionIdGenerator {
    fun next(): StrategyVersionId
}
