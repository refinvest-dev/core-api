package com.refinvest.core.strategy.adapter.out.persistence

import com.refinvest.core.strategy.domain.StrategyId
import com.refinvest.core.strategy.port.outbound.StrategyReadModel
import com.refinvest.core.strategy.port.outbound.StrategyReader
import org.springframework.stereotype.Repository

@Repository
class JpaStrategyReaderAdapter(
    private val strategyJpaReader: StrategyJpaReader,
) : StrategyReader {
    override fun findById(id: StrategyId): StrategyReadModel? =
        strategyJpaReader.findById(id.value)?.let { strategy ->
            StrategyReadModel(
                id = StrategyId(strategy.id),
                name = strategy.name,
                createdAt = strategy.createdAt,
                latestVersionId = null,
            )
        }
}
