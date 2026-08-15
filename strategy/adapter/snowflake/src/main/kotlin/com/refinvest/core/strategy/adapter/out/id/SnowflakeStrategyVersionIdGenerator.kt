package com.refinvest.core.strategy.adapter.out.id

import com.refinvest.core.shared.infrastructure.id.SnowflakeIdGenerator
import com.refinvest.core.strategy.domain.StrategyVersionId
import com.refinvest.core.strategy.port.outbound.StrategyVersionIdGenerator
import org.springframework.stereotype.Component

@Component
class SnowflakeStrategyVersionIdGenerator(
    private val snowflakeIdGenerator: SnowflakeIdGenerator,
) : StrategyVersionIdGenerator {
    override fun next(): StrategyVersionId = StrategyVersionId(snowflakeIdGenerator.next())
}
