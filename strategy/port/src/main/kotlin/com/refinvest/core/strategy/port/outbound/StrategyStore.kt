package com.refinvest.core.strategy.port.outbound

import com.refinvest.core.strategy.domain.Strategy
import com.refinvest.core.strategy.domain.StrategyId

interface StrategyStore {
    fun findById(id: StrategyId): Strategy?

    fun save(strategy: Strategy)
}
