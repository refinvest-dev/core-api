package com.refinvest.core.strategy.adapter.out.persistence

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.strategy.port.outbound.persistence.StrategyOwnerReader
import org.springframework.stereotype.Repository

@Repository
class JpaStrategyOwnerReaderAdapter(
    private val strategyJpaReader: StrategyJpaReader,
) : StrategyOwnerReader {
    override fun findOwnerById(strategyId: Long): MemberId? = strategyJpaReader.findOwnerById(strategyId)?.let(::MemberId)
}
