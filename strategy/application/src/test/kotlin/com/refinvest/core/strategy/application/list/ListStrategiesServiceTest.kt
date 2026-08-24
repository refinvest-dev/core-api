package com.refinvest.core.strategy.application.list

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.strategy.domain.valueobject.StrategyId
import com.refinvest.core.strategy.port.inbound.list.ListStrategiesQuery
import com.refinvest.core.strategy.port.outbound.member.MemberIdProvider
import com.refinvest.core.strategy.port.outbound.persistence.StrategyPageReadModel
import com.refinvest.core.strategy.port.outbound.persistence.StrategyReadModel
import com.refinvest.core.strategy.port.outbound.persistence.StrategyReader
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class ListStrategiesServiceTest {
    @Test
    fun `returns the current member's requested strategy page`() {
        val memberId = MemberId(7L)
        val service = ListStrategiesService(
            strategyReader = object : StrategyReader {
                override fun findById(id: StrategyId): StrategyReadModel? = null

                override fun findByMemberId(
                    memberId: MemberId,
                    page: Int,
                    size: Int,
                ): StrategyPageReadModel = StrategyPageReadModel(listOf(readModel()), 3)
            },
            memberIdProvider = MemberIdProvider { memberId },
        )

        val result = service.execute(ListStrategiesQuery(page = 1, size = 2))

        assertEquals(1, result.page)
        assertEquals(2, result.size)
        assertEquals(3, result.total)
        assertEquals(StrategyId(10L), result.items.single().id)
        assertEquals("volatility hypothesis", result.items.single().name)
    }

    private fun readModel() = StrategyReadModel(
        id = StrategyId(10L),
        memberId = MemberId(7L),
        name = "volatility hypothesis",
        createdAt = Instant.parse("2026-08-20T00:00:00Z"),
        latestVersionId = null,
        versions = emptyList(),
    )
}
