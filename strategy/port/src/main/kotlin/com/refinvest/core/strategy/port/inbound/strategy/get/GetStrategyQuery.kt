package com.refinvest.core.strategy.port.inbound.strategy.get

import com.refinvest.core.strategy.domain.valueobject.StrategyId

data class GetStrategyQuery(
    val strategyId: StrategyId,
)
