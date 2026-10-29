package com.refinvest.core.strategy.port.outbound.persistence

import com.refinvest.core.shared.kernel.member.MemberId

fun interface StrategyOwnerReader {
    fun findOwnerById(strategyId: Long): MemberId?
}
