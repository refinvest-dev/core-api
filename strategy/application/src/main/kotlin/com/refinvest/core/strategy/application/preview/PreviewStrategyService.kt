package com.refinvest.core.strategy.application.preview

import com.refinvest.core.strategy.port.inbound.preview.PreviewCondition
import com.refinvest.core.strategy.port.inbound.preview.PreviewLiteralOperand
import com.refinvest.core.strategy.port.inbound.preview.PreviewMetricOperand
import com.refinvest.core.strategy.port.inbound.preview.PreviewMetricReference
import com.refinvest.core.strategy.port.inbound.preview.PreviewOperand
import com.refinvest.core.strategy.port.inbound.preview.PreviewStrategyCommand
import com.refinvest.core.strategy.port.inbound.preview.PreviewStrategyResult
import com.refinvest.core.strategy.port.inbound.preview.PreviewStrategyUseCase
import com.refinvest.core.strategy.port.outbound.member.MemberIdProvider
import com.refinvest.core.strategy.port.outbound.persistence.StrategyReader
import org.springframework.stereotype.Service
import java.math.BigDecimal

@Service
class PreviewStrategyService(
    private val strategyReader: StrategyReader,
    private val memberIdProvider: MemberIdProvider,
) : PreviewStrategyUseCase {
    override fun execute(command: PreviewStrategyCommand): PreviewStrategyResult? {
        val strategy = strategyReader.findById(command.strategyId) ?: return null
        if (strategy.memberId != memberIdProvider.currentMemberId()) return null

        return PreviewStrategyResult(
            previewText = buildString {
                append("기준 신호 자산은 ${command.primarySignalAsset ?: "미지정"}입니다. ")
                append(conditionsText(command.conditions))
                append(", ${command.lag ?: 0} Signal Session 후 ")
                append("${command.executionAsset ?: "실행 자산"}를 매수해 ")
                append("${command.holdingSignalSessions ?: 0} Signal Session 보유합니다.")
            },
        )
    }

    private fun conditionsText(conditions: List<PreviewCondition>): String =
        conditions.takeIf(List<PreviewCondition>::isNotEmpty)
            ?.joinToString(separator = " ") { condition ->
                logicalCombinatorText(condition.logicalCombinator) + conditionText(condition)
            }
            ?: "조건을 입력해 주세요"

    private fun logicalCombinatorText(logicalCombinator: String?): String = when (logicalCombinator) {
        "AND" -> "그리고 "
        "OR" -> "또는 "
        else -> ""
    }

    private fun conditionText(condition: PreviewCondition): String =
        "${metricReferenceText(condition.operandA)} " +
            "${operandText(condition.operandB, condition.operandA?.metric)} ${operatorText(condition.operator)}"

    private fun metricReferenceText(reference: PreviewMetricReference?): String {
        val asset = reference?.asset ?: "자산"
        return when (reference?.metric) {
            "SIMPLE" -> asset
            "RETURN" -> "${asset}의 ${reference.window ?: 0}일 수익률"
            "CHANGE" -> "${asset}의 ${reference.window ?: 0}일 변화율"
            null -> asset
            else -> "${asset}의 ${reference.metric}"
        }
    }

    private fun operatorText(operator: String?): String = when (operator) {
        "LT" -> "미만"
        "GT" -> "초과"
        "LTE" -> "이하"
        "GTE" -> "이상"
        else -> "비교"
    }

    private fun operandText(operand: PreviewOperand?, operandAMetric: String?): String = when (operand) {
        is PreviewLiteralOperand -> literalText(operand.value, operandAMetric)
        is PreviewMetricOperand -> metricReferenceText(operand.metricReference)
        null -> "값"
    }

    private fun literalText(value: String, operandAMetric: String?): String =
        value.toBigDecimalOrNull()?.takeIf { operandAMetric in PERCENT_METRICS }
            ?.multiply(BigDecimal(100))
            ?.stripTrailingZeros()
            ?.toPlainString()
            ?.plus("%")
            ?: value

    private companion object {
        val PERCENT_METRICS = setOf("RETURN", "CHANGE")
    }
}
