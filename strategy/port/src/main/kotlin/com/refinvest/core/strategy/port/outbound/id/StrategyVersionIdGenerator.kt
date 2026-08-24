package com.refinvest.core.strategy.port.outbound.id

import com.refinvest.core.strategy.domain.valueobject.StrategyVersionId

fun interface StrategyVersionIdGenerator {
    fun next(): StrategyVersionId
}
