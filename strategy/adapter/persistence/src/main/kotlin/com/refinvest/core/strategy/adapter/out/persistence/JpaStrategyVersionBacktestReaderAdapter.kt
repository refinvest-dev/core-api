package com.refinvest.core.strategy.adapter.out.persistence

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.strategy.port.outbound.persistence.version.StrategyVersionBacktestReadModel
import com.refinvest.core.strategy.port.outbound.persistence.version.StrategyVersionBacktestReader
import org.springframework.stereotype.Repository

@Repository
class JpaStrategyVersionBacktestReaderAdapter(private val strategyVersionJpaReader: StrategyVersionJpaReader) : StrategyVersionBacktestReader {
    override fun findById(strategyVersionId: Long): StrategyVersionBacktestReadModel? =
        strategyVersionJpaReader.findById(strategyVersionId)?.let { version ->
            val parent = version.strategy
            val ownerMemberId = MemberId(parent.memberId)
            val conditions = version.conditions.toList()
            StrategyReadModelMapper.toBacktestReadModel(version, parent.id, ownerMemberId, conditions)
        }
}
