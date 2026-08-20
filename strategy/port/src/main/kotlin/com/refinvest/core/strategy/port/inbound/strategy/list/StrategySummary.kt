package com.refinvest.core.strategy.port.inbound.strategy.list

import com.refinvest.core.strategy.domain.valueobject.StrategyId
import com.refinvest.core.strategy.domain.valueobject.StrategyVersionId
import java.time.Instant

data class StrategySummary(
    val id: StrategyId,
    val name: String,
    val createdAt: Instant,
    val latestVersionId: StrategyVersionId?,
)
