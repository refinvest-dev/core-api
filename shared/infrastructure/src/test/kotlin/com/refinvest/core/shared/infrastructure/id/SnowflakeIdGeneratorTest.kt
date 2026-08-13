package com.refinvest.core.shared.infrastructure.id

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SnowflakeIdGeneratorTest {
    @Test
    fun `generates ordered unique positive ids`() {
        val generator = SnowflakeIdGenerator(nodeId = 7)

        val ids = List(1_000) { generator.next() }

        assertEquals(ids.size, ids.toSet().size)
        assertEquals(ids.sorted(), ids)
        assertTrue(ids.all { it > 0 })
    }

    @Test
    fun `rejects an invalid node id`() {
        assertFailsWith<IllegalArgumentException> { SnowflakeIdGenerator(nodeId = 1_024) }
    }

    @Test
    fun `rejects a clock before the custom epoch`() {
        val clock = Clock.fixed(Instant.parse("2023-12-31T23:59:59Z"), ZoneOffset.UTC)

        assertFailsWith<IllegalStateException> { SnowflakeIdGenerator(0, clock).next() }
    }
}
