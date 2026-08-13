package com.refinvest.core.strategy.port.inbound.strategy.get

import com.refinvest.core.strategy.domain.StrategyId

data class GetStrategyQuery(
    val strategyId: StrategyId,
)
