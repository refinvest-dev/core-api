package com.refinvest.core.backtest.adapter.out.persistence

import com.refinvest.core.backtest.domain.backtest.BacktestResult
import com.refinvest.core.backtest.domain.exception.LegacyBacktestResultPayloadException
import org.springframework.stereotype.Component
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@Component
class BacktestResultPayloadMapper(
    private val objectMapper: ObjectMapper,
) {
    private val equivalenceReader = objectMapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
    private val valueComparator = Comparator<JsonNode> { first, second ->
        if (first.isNumber && second.isNumber) first.decimalValue().compareTo(second.decimalValue())
        else if (first == second) 0 else 1
    }

    fun serialize(result: BacktestResult): String = objectMapper.writeValueAsString(result)

    fun equivalent(first: String, second: String): Boolean =
        first == second || equivalenceReader.readTree(first).equals(valueComparator, equivalenceReader.readTree(second))

    fun deserialize(payload: String): BacktestResult {
        val node = objectMapper.readTree(payload)
        if (!node.hasVisualizationFields()) {
            throw LegacyBacktestResultPayloadException()
        }
        return objectMapper.readValue(payload, BacktestResult::class.java)
    }

    private fun JsonNode.hasVisualizationFields(): Boolean =
        path("signalExecutionMarketRelation").isTextual &&
            path("benchmark").path("primary").path("asset").isTextual &&
            path("benchmark").path("primary").path("equityCurve").isArray
}
