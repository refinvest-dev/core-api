package com.refinvest.core.strategy.adapter.out.id

import com.refinvest.core.shared.infrastructure.id.SnowflakeIdGenerator
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SnowflakeStrategyIdGeneratorTest {
    @Test
    fun `wraps a Snowflake value in a StrategyId`() {
        val generator = SnowflakeStrategyIdGenerator(SnowflakeIdGenerator(nodeId = 1))

        val first = generator.next()
        val second = generator.next()

        assertTrue(first.value > 0)
        assertNotEquals(first, second)
    }
}
