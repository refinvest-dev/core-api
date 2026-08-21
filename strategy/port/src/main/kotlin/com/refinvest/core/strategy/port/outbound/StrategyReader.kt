package com.refinvest.core.strategy.port.outbound

import com.refinvest.core.strategy.domain.valueobject.StrategyId
import com.refinvest.core.shared.kernel.member.MemberId

fun interface StrategyReader {
    fun findById(id: StrategyId): StrategyReadModel?

    fun findByMemberId(
        memberId: MemberId,
        page: Int,
        size: Int,
    ): StrategyPageReadModel = StrategyPageReadModel(emptyList(), 0)
}
