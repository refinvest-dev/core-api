package com.refinvest.core.strategy.application.get

import com.refinvest.core.strategy.domain.valueobject.StrategyId
import com.refinvest.core.strategy.port.inbound.get.GetStrategyQuery
import com.refinvest.core.strategy.port.outbound.member.MemberIdProvider
import com.refinvest.core.strategy.port.outbound.persistence.StrategyPageReadModel
import com.refinvest.core.strategy.port.outbound.persistence.StrategyReadModel
import com.refinvest.core.strategy.port.outbound.persistence.StrategyReader
import com.refinvest.core.shared.kernel.member.MemberId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GetStrategyServiceTest {
    @Test
    fun `returns the reader projection as a use case result`() {
        val id = StrategyId(1L)
        val service = GetStrategyService(
            strategyReader {
                StrategyReadModel(id, MemberId(1L), "volatility hypothesis", Instant.parse("2026-08-11T00:00:00Z"), null, emptyList())
            },
            MemberIdProvider { MemberId(1L) },
        )

        val result = service.execute(GetStrategyQuery(id))

        assertEquals(id, result?.id)
        assertEquals("volatility hypothesis", result?.name)
    }

    @Test
    fun `returns null when the reader cannot find the strategy`() {
        val service = GetStrategyService(strategyReader { null }, MemberIdProvider { MemberId(1L) })

        assertNull(service.execute(GetStrategyQuery(StrategyId(1L))))
    }

    @Test
    fun `returns null when the strategy belongs to another member`() {
        val id = StrategyId(1L)
        val service = GetStrategyService(
            strategyReader { StrategyReadModel(id, MemberId(2L), "private", Instant.now(), null, emptyList()) },
            MemberIdProvider { MemberId(1L) },
        )

        assertNull(service.execute(GetStrategyQuery(id)))
    }

    private fun strategyReader(
        findById: (StrategyId) -> StrategyReadModel?,
    ): StrategyReader = object : StrategyReader {
        override fun findById(id: StrategyId): StrategyReadModel? = findById(id)

        override fun findByMemberId(memberId: MemberId, page: Int, size: Int): StrategyPageReadModel =
            error("findByMemberId is not used by GetStrategyService")
    }
}
