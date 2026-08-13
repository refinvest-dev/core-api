package com.refinvest.core.strategy.port.outbound

import com.refinvest.core.strategy.domain.StrategyId
import com.refinvest.core.strategy.domain.StrategyVersionId
import java.time.Instant

fun interface StrategyReader {
    fun findById(id: StrategyId): StrategyReadModel?
}

data class StrategyReadModel(
    val id: StrategyId,
    val name: String,
    val createdAt: Instant,
    val latestVersionId: StrategyVersionId?,
)
