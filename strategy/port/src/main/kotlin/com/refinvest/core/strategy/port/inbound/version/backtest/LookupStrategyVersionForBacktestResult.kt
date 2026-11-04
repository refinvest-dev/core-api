package com.refinvest.core.strategy.port.inbound.version.backtest

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.strategy.port.common.backtest.StrategyVersionForBacktest

data class LookupStrategyVersionForBacktestResult(
    val strategyId: Long,
    val ownerMemberId: MemberId,
    val assetSymbols: Set<String>,
    val definition: StrategyVersionForBacktest? = null,
)
