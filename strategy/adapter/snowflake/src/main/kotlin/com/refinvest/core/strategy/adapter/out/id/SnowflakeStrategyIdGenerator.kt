package com.refinvest.core.strategy.adapter.out.id

import com.refinvest.core.shared.infrastructure.id.SnowflakeIdGenerator
import com.refinvest.core.strategy.domain.valueobject.StrategyId
import com.refinvest.core.strategy.port.outbound.id.StrategyIdGenerator
import org.springframework.stereotype.Component

@Component
class SnowflakeStrategyIdGenerator(
    private val snowflakeIdGenerator: SnowflakeIdGenerator,
) : StrategyIdGenerator {
    override fun next(): StrategyId = StrategyId(snowflakeIdGenerator.next())
}
