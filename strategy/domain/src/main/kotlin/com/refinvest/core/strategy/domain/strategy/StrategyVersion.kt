package com.refinvest.core.strategy.domain.strategy

import com.refinvest.core.strategy.domain.valueobject.AssetSymbol
import com.refinvest.core.strategy.domain.valueobject.Condition
import com.refinvest.core.strategy.domain.valueobject.SignalSessions
import com.refinvest.core.strategy.domain.valueobject.StrategyId
import com.refinvest.core.strategy.domain.valueobject.StrategyVersionId
import com.refinvest.core.strategy.domain.valueobject.TimeBasedExit
import com.refinvest.core.strategy.domain.policy.PositionPolicy

import com.refinvest.core.common.domain.DomainEntity
import java.time.Instant

class StrategyVersion private constructor(
    id: StrategyVersionId,
    val strategyId: StrategyId,
    val createdAt: Instant,
    val primarySignalAsset: AssetSymbol,
    conditions: List<Condition>,
    val executionAsset: AssetSymbol,
    val lag: SignalSessions,
    val exit: TimeBasedExit,
) : DomainEntity<StrategyVersionId>(id) {
    val conditions: List<Condition> = conditions.toList()
    val positionPolicy: PositionPolicy = PositionPolicy

    init {
        require(this.conditions.isNotEmpty()) { "conditions must contain at least one condition" }
        require(executionAsset in EXECUTION_ASSETS) { "$executionAsset must be an execution asset" }
        require(this.conditions.first().logicalCombinator == null) {
            "The first condition must not have a logicalCombinator"
        }
        require(this.conditions.drop(1).all { it.logicalCombinator != null }) {
            "Every condition after the first requires a logicalCombinator"
        }
    }

    companion object {
        private val EXECUTION_ASSETS = setOf(
            AssetSymbol.QQQ,
            AssetSymbol.SPY,
            AssetSymbol.TQQQ,
            AssetSymbol.SOXL,
            AssetSymbol.BTCUSDT,
        )

        fun create(
            id: StrategyVersionId,
            strategyId: StrategyId,
            createdAt: Instant,
            primarySignalAsset: AssetSymbol,
            conditions: List<Condition>,
            executionAsset: AssetSymbol,
            lag: SignalSessions,
            exit: TimeBasedExit,
        ): StrategyVersion = StrategyVersion(
            id = id,
            strategyId = strategyId,
            createdAt = createdAt,
            primarySignalAsset = primarySignalAsset,
            conditions = conditions,
            executionAsset = executionAsset,
            lag = lag,
            exit = exit,
        )
    }
}
