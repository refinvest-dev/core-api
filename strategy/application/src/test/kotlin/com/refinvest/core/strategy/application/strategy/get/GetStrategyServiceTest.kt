package com.refinvest.core.strategy.application.strategy.get

import com.refinvest.core.strategy.domain.valueobject.StrategyId
import com.refinvest.core.strategy.port.inbound.strategy.get.GetStrategyQuery
import com.refinvest.core.strategy.port.outbound.StrategyReadModel
import com.refinvest.core.strategy.port.outbound.StrategyReader
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GetStrategyServiceTest {
    @Test
    fun `returns the reader projection as a use case result`() {
        val id = StrategyId(1L)
        val service = GetStrategyService(
            StrategyReader {
                StrategyReadModel(id, "volatility hypothesis", Instant.parse("2026-08-11T00:00:00Z"), null, emptyList())
            },
        )

        val result = service.execute(GetStrategyQuery(id))

        assertEquals(id, result?.id)
        assertEquals("volatility hypothesis", result?.name)
    }

    @Test
    fun `returns null when the reader cannot find the strategy`() {
        val service = GetStrategyService(StrategyReader { null })

        assertNull(service.execute(GetStrategyQuery(StrategyId(1L))))
    }
}
