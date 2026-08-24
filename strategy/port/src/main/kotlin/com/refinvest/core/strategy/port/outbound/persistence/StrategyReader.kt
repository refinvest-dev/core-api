package com.refinvest.core.strategy.port.outbound.persistence

import com.refinvest.core.strategy.domain.valueobject.StrategyId
import com.refinvest.core.shared.kernel.member.MemberId

interface StrategyReader {
    fun findById(id: StrategyId): StrategyReadModel?

    fun findByMemberId(
        memberId: MemberId,
        page: Int,
        size: Int,
    ): StrategyPageReadModel
}
