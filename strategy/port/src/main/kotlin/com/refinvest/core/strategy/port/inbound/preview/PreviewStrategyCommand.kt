package com.refinvest.core.strategy.port.inbound.preview

import com.refinvest.core.strategy.domain.valueobject.StrategyId

data class PreviewStrategyCommand(
    val strategyId: StrategyId,
    val primarySignalAsset: String?,
    val conditions: List<PreviewCondition>,
    val executionAsset: String?,
    val lag: Int?,
    val holdingSignalSessions: Int?,
)

data class PreviewCondition(
    val operator: String?,
    val logicalCombinator: String?,
    val operandA: PreviewMetricReference?,
    val operandB: PreviewOperand?,
)

data class PreviewMetricReference(
    val asset: String?,
    val metric: String?,
    val window: Int?,
)

sealed interface PreviewOperand

data class PreviewLiteralOperand(
    val value: String,
) : PreviewOperand

data class PreviewMetricOperand(
    val metricReference: PreviewMetricReference,
) : PreviewOperand
