package com.refinvest.core.strategy.adapter.out.persistence

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.strategy.domain.valueobject.StrategyId
import com.refinvest.core.strategy.port.outbound.persistence.StrategyReadModel
import com.refinvest.core.strategy.port.outbound.persistence.StrategyReader
import com.refinvest.core.strategy.port.outbound.persistence.StrategyPageReadModel
import org.springframework.stereotype.Repository
import org.springframework.data.domain.PageRequest

@Repository
class JpaStrategyReaderAdapter(
    private val strategyJpaReader: StrategyJpaReader,
) : StrategyReader {
    override fun findById(id: StrategyId): StrategyReadModel? =
        strategyJpaReader.findById(id.value)?.let(StrategyReadModelMapper::toReadModel)

    override fun findByMemberId(
        memberId: MemberId,
        page: Int,
        size: Int,
    ): StrategyPageReadModel = strategyJpaReader
        .findAllByMemberIdOrderByCreatedAtDesc(memberId.value, PageRequest.of(page, size))
        .let { strategies -> StrategyPageReadModel(strategies.content.map(StrategyReadModelMapper::toReadModel), strategies.totalElements) }

}
