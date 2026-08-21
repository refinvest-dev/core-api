package com.refinvest.core.strategy.application.strategy.create

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.strategy.domain.Strategy
import com.refinvest.core.strategy.domain.valueobject.StrategyId
import com.refinvest.core.strategy.port.inbound.strategy.create.CreateStrategyCommand
import com.refinvest.core.strategy.port.outbound.MemberIdProvider
import com.refinvest.core.strategy.port.outbound.StrategyIdGenerator
import com.refinvest.core.strategy.port.outbound.StrategyStore
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CreateStrategyServiceTest {
    @Test
    fun `creates an empty strategy saves it and returns its id`() {
        val id = StrategyId(1L)
        val memberId = MemberId(2L)
        var saved: Strategy? = null
        val service = CreateStrategyService(
            strategyStore = object : StrategyStore {
                override fun findById(id: StrategyId): Strategy? = null

                override fun save(strategy: Strategy) {
                    saved = strategy
                }
            },
            strategyIdGenerator = StrategyIdGenerator { id },
            memberIdProvider = MemberIdProvider { memberId },
            clock = Clock.fixed(Instant.parse("2026-08-11T00:00:00Z"), ZoneOffset.UTC),
        )

        val result = service.execute(CreateStrategyCommand("volatility hypothesis"))

        assertEquals(id, result.id)
        assertEquals(memberId, saved?.memberId)
        assertEquals("volatility hypothesis", saved?.name)
        assertTrue(saved?.versions?.isEmpty() == true)
    }
}
