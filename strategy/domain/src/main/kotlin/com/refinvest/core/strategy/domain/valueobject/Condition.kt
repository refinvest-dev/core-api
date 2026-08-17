package com.refinvest.core.strategy.domain.valueobject

data class Condition(
    val operator: ComparisonOperator,
    val logicalCombinator: LogicalCombinator? = null,
    val operandA: MetricReference,
    val operandB: ConditionOperand,
)
