package com.refinvest.core.strategy.domain

enum class AssetSymbol {
    QQQ,
    SPY,
    TQQQ,
    SOXL,
    BTCUSDT,
    VIX;

    companion object {
        fun from(value: String): AssetSymbol =
            entries.firstOrNull { it.name == value }
                ?: throw IllegalArgumentException("Unsupported MVP asset: $value")
    }
}

enum class MetricType { SIMPLE, RETURN, CHANGE }

data class MetricReference(
    val asset: AssetSymbol,
    val metric: MetricType,
    val window: Int? = null,
) {
    init {
        when (metric) {
            MetricType.SIMPLE -> require(window == null) { "SIMPLE metric must not have a window" }
            MetricType.RETURN, MetricType.CHANGE -> require(window != null && window > 0) {
                "$metric metric requires a positive window"
            }
        }
    }
}

sealed interface ConditionOperand

data class MetricOperand(val reference: MetricReference) : ConditionOperand

data class LiteralValue(val value: Double) : ConditionOperand

enum class ComparisonOperator { LT, GT, LTE, GTE }

enum class LogicalCombinator { AND, OR }

data class Condition(
    val operator: ComparisonOperator,
    val logicalCombinator: LogicalCombinator? = null,
    val operandA: MetricReference,
    val operandB: ConditionOperand,
)

@JvmInline
value class SignalSessions(val value: Int) {
    init {
        require(value >= 0) { "SignalSessions must be zero or greater" }
    }
}

@JvmInline
value class TimeBasedExit(val holdingSignalSessions: Int) {
    init {
        require(holdingSignalSessions > 0) { "holdingSignalSessions must be positive" }
    }
}

data object PositionPolicy {
    const val longOnly: Boolean = true
    const val singlePosition: Boolean = true
    const val duplicateEntry: String = "IGNORE"
}
