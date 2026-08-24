package com.refinvest.core.strategy.port.inbound.define

import com.refinvest.core.strategy.domain.valueobject.AssetSymbol
import com.refinvest.core.strategy.domain.valueobject.Condition
import com.refinvest.core.strategy.domain.valueobject.SignalSessions
import com.refinvest.core.strategy.domain.valueobject.StrategyId
import com.refinvest.core.strategy.domain.valueobject.TimeBasedExit

data class DefineStrategyVersionCommand(
    val strategyId: StrategyId,
    val primarySignalAsset: AssetSymbol,
    val conditions: List<Condition>,
    val executionAsset: AssetSymbol,
    val lag: SignalSessions,
    val exit: TimeBasedExit,
)
