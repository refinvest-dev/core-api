package com.refinvest.core.strategy.port.inbound.strategy.define

import com.refinvest.core.strategy.domain.AssetSymbol
import com.refinvest.core.strategy.domain.Condition
import com.refinvest.core.strategy.domain.SignalSessions
import com.refinvest.core.strategy.domain.StrategyId
import com.refinvest.core.strategy.domain.StrategyVersionId
import com.refinvest.core.strategy.domain.TimeBasedExit
import java.time.Instant

data class DefineStrategyVersionResult(
    val id: StrategyVersionId,
    val strategyId: StrategyId,
    val createdAt: Instant,
    val primarySignalAsset: AssetSymbol,
    val conditions: List<Condition>,
    val executionAsset: AssetSymbol,
    val lag: SignalSessions,
    val exit: TimeBasedExit,
)
