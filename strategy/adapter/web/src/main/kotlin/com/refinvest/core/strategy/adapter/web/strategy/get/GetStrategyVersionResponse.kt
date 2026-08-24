package com.refinvest.core.strategy.adapter.web.strategy.get

import com.refinvest.core.strategy.adapter.web.strategy.define.ConditionResponse
import com.refinvest.core.strategy.adapter.web.strategy.define.TimeBasedExitResponse
import com.refinvest.core.strategy.port.inbound.get.GetStrategyVersionResult
import java.time.Instant

data class GetStrategyVersionResponse(
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
        fun from(strategyId: String, result: GetStrategyVersionResult): GetStrategyVersionResponse =
            GetStrategyVersionResponse(
                id = result.id.value.toString(),
                strategyId = strategyId,
                createdAt = result.createdAt,
                primarySignalAsset = result.primarySignalAsset.name,
                conditions = result.conditions.map(ConditionResponse::from),
                executionAsset = result.executionAsset.name,
                lag = result.lag.value,
                exit = TimeBasedExitResponse(result.exit.holdingSignalSessions),
            )
    }
}
