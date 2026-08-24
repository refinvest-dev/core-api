package com.refinvest.core.strategy.port.outbound.persistence

import com.refinvest.core.strategy.domain.Strategy
import com.refinvest.core.strategy.domain.valueobject.StrategyId

interface StrategyStore {
    fun findById(id: StrategyId): Strategy?

    fun save(strategy: Strategy)
}
