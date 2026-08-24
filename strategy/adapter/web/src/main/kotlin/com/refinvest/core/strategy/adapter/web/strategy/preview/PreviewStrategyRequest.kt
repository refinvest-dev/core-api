package com.refinvest.core.strategy.adapter.web.strategy.preview

import com.refinvest.core.strategy.domain.valueobject.StrategyId
import com.refinvest.core.strategy.port.inbound.preview.PreviewStrategyCommand

data class PreviewStrategyRequest(
    val primarySignalAsset: String? = null,
    val conditions: List<PreviewConditionRequest> = emptyList(),
    val executionAsset: String? = null,
    val lag: Int? = null,
    val exit: PreviewTimeBasedExitRequest? = null,
) {
    fun toCommand(strategyId: StrategyId): PreviewStrategyCommand = PreviewStrategyCommand(
        strategyId = strategyId,
        primarySignalAsset = primarySignalAsset,
        conditions = conditions.map(PreviewConditionRequest::toCommand),
        executionAsset = executionAsset,
        lag = lag,
        holdingSignalSessions = exit?.holdingSignalSessions,
    )
}
