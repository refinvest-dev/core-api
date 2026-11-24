package com.refinvest.core.shared.infrastructure.http

import kotlin.test.*

class TraceContextHeadersTest {
    private val parent="00-12345678901234567890123456789012-1234567890123456-01"

    @Test
    fun `valid context keeps trace ID and gets a new span`() {
        val trace=TraceContextHeaders.start(listOf(parent),listOf("one=value","two=other"))
        assertEquals("12345678901234567890123456789012",trace.traceId)
        assertNotEquals(parent,trace.parent)
        assertEquals("one=value,two=other",trace.state)
        val child=TraceContextHeaders.child(trace.parent,trace.state)
        assertEquals(trace.traceId,child.traceId)
        assertNotEquals(trace.parent,child.parent)
    }

    @Test
    fun `invalid or duplicate parent drops state and starts fresh`() {
        for(parents in listOf(emptyList(),listOf("bad"),listOf(parent,parent),listOf("0".repeat(513)))) {
            val trace=TraceContextHeaders.start(parents,listOf("one=value"))
            assertNotEquals("12345678901234567890123456789012",trace.traceId)
            assertTrue(Regex("[0-9a-f]{32}").matches(trace.traceId))
            assertNull(trace.state)
        }
    }
}
