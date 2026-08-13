package com.refinvest.core.strategy.port.inbound.strategy.get

import com.refinvest.core.strategy.domain.StrategyId
import com.refinvest.core.strategy.domain.StrategyVersionId
import java.time.Instant

data class GetStrategyResult(
    val id: StrategyId,
    val name: String,
    val createdAt: Instant,
    val latestVersionId: StrategyVersionId?,
)
