package com.refinvest.core.backtest.adapter.out.persistence

import com.refinvest.core.backtest.domain.backtest.BacktestResult
import com.refinvest.core.backtest.domain.exception.LegacyBacktestResultPayloadException
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@Component
class BacktestResultPayloadMapper(
    private val objectMapper: ObjectMapper,
) {
    fun serialize(result: BacktestResult): String = objectMapper.writeValueAsString(result)

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
