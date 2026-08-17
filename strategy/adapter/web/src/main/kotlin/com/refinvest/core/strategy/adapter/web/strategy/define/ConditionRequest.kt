package com.refinvest.core.strategy.adapter.web.strategy.define

import tools.jackson.databind.JsonNode
import com.refinvest.core.strategy.domain.valueobject.AssetSymbol
import com.refinvest.core.strategy.domain.valueobject.ComparisonOperator
import com.refinvest.core.strategy.domain.valueobject.Condition
import com.refinvest.core.strategy.domain.valueobject.ConditionOperand
import com.refinvest.core.strategy.domain.valueobject.LiteralValue
import com.refinvest.core.strategy.domain.valueobject.LogicalCombinator
import com.refinvest.core.strategy.domain.valueobject.MetricOperand
import com.refinvest.core.strategy.domain.valueobject.MetricReference
import com.refinvest.core.strategy.domain.valueobject.MetricType
import jakarta.validation.Valid

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
