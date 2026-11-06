package com.refinvest.core.strategy.port.common.backtest

/** Immutable provider-neutral definition shared by the Reader projection and cross-feature lookup result. */
data class StrategyVersionForBacktest(
    val primarySignalAsset: String,
    val conditions: List<StrategyConditionForBacktest>,
    val executionAsset: String,
    val lag: Int,
    val holdingSignalSessions: Int,
)

data class StrategyConditionForBacktest(
    val operator: String,
    val logicalCombinator: String?,
    val operandA: StrategyMetricReferenceForBacktest,
    val operandB: StrategyConditionOperandForBacktest,
)

sealed interface StrategyConditionOperandForBacktest

data class StrategyMetricOperandForBacktest(val value: StrategyMetricReferenceForBacktest) : StrategyConditionOperandForBacktest

data class StrategyLiteralOperandForBacktest(val value: Double) : StrategyConditionOperandForBacktest

data class StrategyMetricReferenceForBacktest(
    val asset: String,
    val metric: String,
    val window: Int?,
)
