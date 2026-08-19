package com.refinvest.core.strategy.adapter.out.persistence

import com.refinvest.core.strategy.domain.valueobject.StrategyId
import com.refinvest.core.strategy.domain.valueobject.StrategyVersionId
import com.refinvest.core.strategy.port.outbound.StrategyVersionOwnerReadModel
import com.refinvest.core.strategy.port.outbound.StrategyVersionOwnerReader
import org.springframework.stereotype.Repository

@Repository
class JpaStrategyVersionOwnerReaderAdapter(
    private val strategyVersionJpaReader: StrategyVersionJpaReader,
) : StrategyVersionOwnerReader {
    override fun findById(id: StrategyVersionId): StrategyVersionOwnerReadModel? =
        strategyVersionJpaReader.findById(id.value)?.let { version ->
            StrategyVersionOwnerReadModel(
                strategyVersionId = StrategyVersionId(version.id),
                strategyId = StrategyId(version.strategy.id),
            )
        }
}
