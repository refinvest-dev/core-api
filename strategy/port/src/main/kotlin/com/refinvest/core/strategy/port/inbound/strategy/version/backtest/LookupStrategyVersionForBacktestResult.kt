package com.refinvest.core.strategy.port.inbound.strategy.version.backtest

import com.refinvest.core.shared.kernel.member.MemberId

data class LookupStrategyVersionForBacktestResult(
    val strategyId: Long,
    val ownerMemberId: MemberId,
    val assetSymbols: Set<String>,
)
