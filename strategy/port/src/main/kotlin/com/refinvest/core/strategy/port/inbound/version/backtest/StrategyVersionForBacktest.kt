package com.refinvest.core.strategy.port.inbound.version.backtest

/** Provider-neutral StrategyVersion representation consumed by the backtest application boundary. */
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
