package com.refinvest.core.strategy.port.inbound.get

import com.refinvest.core.strategy.domain.valueobject.StrategyId

data class GetStrategyQuery(
    val strategyId: StrategyId,
)
