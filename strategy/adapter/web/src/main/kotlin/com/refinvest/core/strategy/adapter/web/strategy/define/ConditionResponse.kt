package com.refinvest.core.strategy.adapter.web.strategy.define

import com.refinvest.core.strategy.domain.valueobject.Condition
import com.refinvest.core.strategy.domain.valueobject.LiteralValue
import com.refinvest.core.strategy.domain.valueobject.MetricOperand

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
