package com.refinvest.core.strategy.adapter.out.persistence

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.strategy.port.outbound.persistence.version.StrategyVersionBacktestReadModel
import com.refinvest.core.strategy.port.outbound.persistence.version.StrategyVersionBacktestReader
import org.springframework.stereotype.Repository

@Repository
class JpaStrategyVersionBacktestReaderAdapter(
    private val strategyVersionJpaReader: StrategyVersionJpaReader,
) : StrategyVersionBacktestReader {
    override fun findById(strategyVersionId: Long): StrategyVersionBacktestReadModel? =
        strategyVersionJpaReader.findById(strategyVersionId)?.let { version ->
            StrategyVersionBacktestReadModel(
                strategyId = version.strategy.id,
                ownerMemberId = MemberId(version.strategy.memberId),
                assetSymbols = buildSet {
                    add(version.primarySignalAsset)
                    add(version.executionAsset)
                    version.conditions.forEach { condition ->
                        add(condition.operandAAsset)
                        condition.operandBAsset?.let(::add)
                    }
                },
            )
        }
}
