package com.refinvest.core.strategy.adapter.web.strategy.define

import com.refinvest.core.strategy.domain.valueobject.AssetSymbol
import com.refinvest.core.strategy.domain.valueobject.SignalSessions
import com.refinvest.core.strategy.domain.valueobject.StrategyId
import com.refinvest.core.strategy.domain.valueobject.TimeBasedExit
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
