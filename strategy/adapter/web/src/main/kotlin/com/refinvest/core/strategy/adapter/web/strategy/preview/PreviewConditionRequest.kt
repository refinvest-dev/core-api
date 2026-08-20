package com.refinvest.core.strategy.adapter.web.strategy.preview

import com.refinvest.core.strategy.port.inbound.strategy.preview.PreviewCondition
import com.refinvest.core.strategy.port.inbound.strategy.preview.PreviewLiteralOperand
import com.refinvest.core.strategy.port.inbound.strategy.preview.PreviewMetricOperand
import com.refinvest.core.strategy.port.inbound.strategy.preview.PreviewOperand
import tools.jackson.databind.JsonNode

data class PreviewConditionRequest(
    val operator: String? = null,
    val logicalCombinator: String? = null,
    val operandA: PreviewMetricReferenceRequest? = null,
    val operandB: JsonNode? = null,
) {
    fun toCommand(): PreviewCondition = PreviewCondition(
        operator = operator,
        logicalCombinator = logicalCombinator,
        operandA = operandA?.toCommand(),
        operandB = operandB?.toCommand(),
    )

    private fun JsonNode.toCommand(): PreviewOperand? = when {
        isNumber || isTextual -> PreviewLiteralOperand(asText())
        isObject -> PreviewMetricReferenceRequest(
            asset = get("asset")?.takeUnless(JsonNode::isNull)?.asText(),
            metric = get("metric")?.takeUnless(JsonNode::isNull)?.asText(),
            window = get("window")?.takeUnless(JsonNode::isNull)?.asInt(),
        ).toCommand().let(::PreviewMetricOperand)
        else -> null
    }
}
