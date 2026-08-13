package com.refinvest.core.strategy.port.inbound.strategy.create

import com.refinvest.core.strategy.domain.StrategyId
import java.time.Instant

data class CreateStrategyResult(
    val id: StrategyId,
    val createdAt: Instant,
)
