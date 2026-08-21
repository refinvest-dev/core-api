package com.refinvest.core.strategy.port.outbound

import com.refinvest.core.shared.kernel.member.MemberId

data class StrategyVersionBacktestReadModel(
    val strategyId: Long,
    val ownerMemberId: MemberId,
    val assetSymbols: Set<String>,
)
