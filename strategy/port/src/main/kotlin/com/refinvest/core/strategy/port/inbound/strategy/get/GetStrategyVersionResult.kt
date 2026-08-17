package com.refinvest.core.strategy.port.inbound.strategy.get

import com.refinvest.core.strategy.domain.valueobject.AssetSymbol
import com.refinvest.core.strategy.domain.valueobject.Condition
import com.refinvest.core.strategy.domain.valueobject.SignalSessions
import com.refinvest.core.strategy.domain.valueobject.StrategyVersionId
import com.refinvest.core.strategy.domain.valueobject.TimeBasedExit
import java.time.Instant

data class GetStrategyVersionResult(
    val id: StrategyVersionId,
    val createdAt: Instant,
    val primarySignalAsset: AssetSymbol,
    val conditions: List<Condition>,
    val executionAsset: AssetSymbol,
    val lag: SignalSessions,
    val exit: TimeBasedExit,
)
