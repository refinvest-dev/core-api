package com.refinvest.core.strategy.port.outbound

import com.refinvest.core.strategy.domain.valueobject.StrategyId
import com.refinvest.core.strategy.domain.valueobject.StrategyVersionId
import java.time.Instant

data class StrategyReadModel(
    val id: StrategyId,
    val name: String,
    val createdAt: Instant,
    val latestVersionId: StrategyVersionId?,
    val versions: List<StrategyVersionReadModel>,
)
