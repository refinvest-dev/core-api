package com.refinvest.core.strategy.port.inbound.create

import com.refinvest.core.strategy.domain.valueobject.StrategyId
import java.time.Instant

data class CreateStrategyResult(
    val id: StrategyId,
    val createdAt: Instant,
)
