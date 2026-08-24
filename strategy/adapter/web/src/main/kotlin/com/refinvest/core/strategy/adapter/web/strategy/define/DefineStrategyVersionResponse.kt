package com.refinvest.core.strategy.adapter.web.strategy.define

import com.refinvest.core.strategy.port.inbound.define.DefineStrategyVersionResult
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
