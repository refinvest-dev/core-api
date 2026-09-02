package com.refinvest.core.strategy.port.outbound.persistence.version

import com.refinvest.core.shared.kernel.member.MemberId

data class StrategyVersionBacktestReadModel(
    val strategyId: Long,
    val ownerMemberId: MemberId,
    val assetSymbols: Set<String>,
    val definition: com.refinvest.core.strategy.port.inbound.version.backtest.StrategyVersionForBacktest,
)
