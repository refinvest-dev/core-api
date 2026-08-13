package com.refinvest.core.strategy.adapter.out.persistence

import com.refinvest.core.strategy.domain.Strategy
import com.refinvest.core.strategy.port.outbound.StrategyStore
import org.springframework.stereotype.Repository

@Repository
class JpaStrategyStoreAdapter(
    private val strategyJpaStore: StrategyJpaStore,
) : StrategyStore {
    override fun save(strategy: Strategy) {
        strategyJpaStore.save(
            StrategyJpaEntity(
                id = strategy.id.value,
                memberId = strategy.memberId.value,
                name = strategy.name,
                createdAt = strategy.createdAt,
            ),
        )
    }
}
