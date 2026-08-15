package com.refinvest.core.strategy.adapter.web.strategy.define

import tools.jackson.databind.JsonNode
import com.refinvest.core.strategy.domain.AssetSymbol
import com.refinvest.core.strategy.domain.ComparisonOperator
import com.refinvest.core.strategy.domain.Condition
import com.refinvest.core.strategy.domain.ConditionOperand
import com.refinvest.core.strategy.domain.LiteralValue
import com.refinvest.core.strategy.domain.LogicalCombinator
import com.refinvest.core.strategy.domain.MetricOperand
import com.refinvest.core.strategy.domain.MetricReference
import com.refinvest.core.strategy.domain.MetricType
import com.refinvest.core.strategy.domain.SignalSessions
import com.refinvest.core.strategy.domain.StrategyId
import com.refinvest.core.strategy.domain.TimeBasedExit
import com.refinvest.core.strategy.port.inbound.strategy.define.DefineStrategyVersionCommand
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull

data class DefineStrategyVersionRequest(
    val primarySignalAsset: String,
    @field:NotEmpty
    val conditions: List<@Valid ConditionRequest>,
    val executionAsset: String,
    @field:Min(0)
    val lag: Int,
    @field:NotNull
    @field:Valid
    val exit: TimeBasedExitRequest,
) {
    fun toCommand(strategyId: StrategyId): DefineStrategyVersionCommand = DefineStrategyVersionCommand(
        strategyId = strategyId,
        primarySignalAsset = AssetSymbol.from(primarySignalAsset),
        conditions = conditions.map(ConditionRequest::toDomain),
        executionAsset = AssetSymbol.from(executionAsset),
        lag = SignalSessions(lag),
        exit = TimeBasedExit(exit.holdingSignalSessions),
    )
}

data class ConditionRequest(
    val operator: String,
    val logicalCombinator: String? = null,
    @field:Valid
    val operandA: MetricReferenceRequest,
    val operandB: JsonNode,
) {
    fun toDomain(): Condition = Condition(
        operator = ComparisonOperator.valueOf(operator),
        logicalCombinator = logicalCombinator?.let(LogicalCombinator::valueOf),
        operandA = operandA.toDomain(),
        operandB = operandB.toDomainOperand(),
    )

    private fun JsonNode.toDomainOperand(): ConditionOperand = when {
        isNumber -> LiteralValue(asDouble())
        isObject -> MetricOperand(
            MetricReference(
                asset = AssetSymbol.from(requiredText("asset")),
                metric = MetricType.valueOf(requiredText("metric")),
                window = get("window")?.takeUnless(JsonNode::isNull)?.asInt(),
            ),
        )
        else -> throw IllegalArgumentException("operandB must be a number or MetricReference")
    }

    private fun JsonNode.requiredText(fieldName: String): String =
        get(fieldName)?.takeUnless(JsonNode::isNull)?.asText()
            ?: throw IllegalArgumentException("operandB.$fieldName is required")
}

data class MetricReferenceRequest(
    val asset: String,
    val metric: String,
    val window: Int? = null,
) {
    fun toDomain(): MetricReference = MetricReference(
        asset = AssetSymbol.from(asset),
        metric = MetricType.valueOf(metric),
        window = window,
    )
}

data class TimeBasedExitRequest(
    @field:Min(1)
    val holdingSignalSessions: Int,
)
