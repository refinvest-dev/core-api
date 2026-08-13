package com.refinvest.core.backtest.adapter.out.id

import com.refinvest.core.backtest.domain.BacktestRunId
import com.refinvest.core.backtest.port.outbound.BacktestRunIdGenerator
import com.refinvest.core.shared.infrastructure.id.SnowflakeIdGenerator
import org.springframework.stereotype.Component

@Component
class SnowflakeBacktestRunIdGenerator(
    private val snowflakeIdGenerator: SnowflakeIdGenerator,
) : BacktestRunIdGenerator {
    override fun next(): BacktestRunId = BacktestRunId(snowflakeIdGenerator.next())
}
