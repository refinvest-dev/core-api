package com.refinvest.core.strategy.adapter.web.strategy.get

import java.time.Instant
import com.refinvest.core.strategy.adapter.web.strategy.define.ConditionResponse
import com.refinvest.core.strategy.adapter.web.strategy.define.TimeBasedExitResponse
import com.refinvest.core.strategy.port.inbound.strategy.get.GetStrategyVersionResult

data class GetStrategyResponse(
    val id: String,
    val name: String,
    val createdAt: Instant,
    val latestVersionId: String?,
    val versions: List<GetStrategyVersionResponse>,
)

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
