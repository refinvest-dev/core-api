package com.refinvest.core.strategy.port.outbound

import com.refinvest.core.strategy.domain.StrategyId
import com.refinvest.core.strategy.domain.StrategyVersionId
import com.refinvest.core.strategy.domain.AssetSymbol
import com.refinvest.core.strategy.domain.Condition
import com.refinvest.core.strategy.domain.SignalSessions
import com.refinvest.core.strategy.domain.TimeBasedExit
import java.time.Instant

fun interface StrategyReader {
    fun findById(id: StrategyId): StrategyReadModel?
}

data class StrategyReadModel(
    val id: StrategyId,
    val name: String,
    val createdAt: Instant,
    val latestVersionId: StrategyVersionId?,
    val versions: List<StrategyVersionReadModel>,
)

data class StrategyVersionReadModel(
    val id: StrategyVersionId,
    val createdAt: Instant,
    val primarySignalAsset: AssetSymbol,
    val conditions: List<Condition>,
    val executionAsset: AssetSymbol,
    val lag: SignalSessions,
    val exit: TimeBasedExit,
)
