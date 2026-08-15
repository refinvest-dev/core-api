package com.refinvest.core.strategy.adapter.web.strategy.define

import com.refinvest.core.strategy.domain.Condition
import com.refinvest.core.strategy.domain.LiteralValue
import com.refinvest.core.strategy.domain.MetricOperand
import com.refinvest.core.strategy.port.inbound.strategy.define.DefineStrategyVersionResult
import java.time.Instant

data class DefineStrategyVersionResponse(
    val id: String,
    val strategyId: String,
    val createdAt: Instant,
    val primarySignalAsset: String,
    val conditions: List<ConditionResponse>,
    val executionAsset: String,
    val lag: Int,
    val exit: TimeBasedExitResponse,
) {
    companion object {
        fun from(result: DefineStrategyVersionResult): DefineStrategyVersionResponse = DefineStrategyVersionResponse(
            id = result.id.value.toString(),
            strategyId = result.strategyId.value.toString(),
            createdAt = result.createdAt,
            primarySignalAsset = result.primarySignalAsset.name,
            conditions = result.conditions.map(ConditionResponse::from),
            executionAsset = result.executionAsset.name,
            lag = result.lag.value,
            exit = TimeBasedExitResponse(result.exit.holdingSignalSessions),
        )
    }
}

data class ConditionResponse(
    val operator: String,
    val logicalCombinator: String?,
    val operandA: MetricReferenceResponse,
    val operandB: Any,
) {
    companion object {
        fun from(condition: Condition): ConditionResponse = ConditionResponse(
            operator = condition.operator.name,
            logicalCombinator = condition.logicalCombinator?.name,
            operandA = MetricReferenceResponse.from(condition.operandA),
            operandB = when (val operandB = condition.operandB) {
                is LiteralValue -> operandB.value
                is MetricOperand -> MetricReferenceResponse.from(operandB.reference)
            },
        )
    }
}

data class MetricReferenceResponse(
    val asset: String,
    val metric: String,
    val window: Int?,
) {
    companion object {
        fun from(reference: com.refinvest.core.strategy.domain.MetricReference): MetricReferenceResponse =
            MetricReferenceResponse(reference.asset.name, reference.metric.name, reference.window)
    }
}

data class TimeBasedExitResponse(
    val holdingSignalSessions: Int,
)
