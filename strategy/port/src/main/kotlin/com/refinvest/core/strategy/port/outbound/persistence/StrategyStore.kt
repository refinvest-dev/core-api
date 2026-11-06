package com.refinvest.core.strategy.port.outbound.persistence

import com.refinvest.core.strategy.domain.Strategy
import com.refinvest.core.strategy.domain.valueobject.StrategyId

interface StrategyStore {
    /** Command-side load: requires an enclosing transaction and locks the root until it completes. */
    fun findById(id: StrategyId): Strategy?

    /** Saves the aggregate, appending new versions without replacing existing immutable versions. */
    fun save(strategy: Strategy)
}
