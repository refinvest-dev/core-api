package com.refinvest.core.backtest.adapter.out.id

import com.refinvest.core.shared.infrastructure.id.SnowflakeIdGenerator
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SnowflakeBacktestRunIdGeneratorTest {
    @Test
    fun `wraps a Snowflake value in a BacktestRunId`() {
        val generator = SnowflakeBacktestRunIdGenerator(SnowflakeIdGenerator(nodeId = 1))

        val first = generator.next()
        val second = generator.next()

        assertTrue(first.value > 0)
        assertNotEquals(first, second)
    }
}
