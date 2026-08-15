package com.refinvest.core.strategy.port.inbound.strategy.define

import com.refinvest.core.strategy.domain.AssetSymbol
import com.refinvest.core.strategy.domain.Condition
import com.refinvest.core.strategy.domain.SignalSessions
import com.refinvest.core.strategy.domain.StrategyId
import com.refinvest.core.strategy.domain.TimeBasedExit

data class DefineStrategyVersionCommand(
    val strategyId: StrategyId,
    val primarySignalAsset: AssetSymbol,
    val conditions: List<Condition>,
    val executionAsset: AssetSymbol,
    val lag: SignalSessions,
    val exit: TimeBasedExit,
)
