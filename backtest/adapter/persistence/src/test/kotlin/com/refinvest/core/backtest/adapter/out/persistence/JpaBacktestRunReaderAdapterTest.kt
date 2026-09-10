package com.refinvest.core.backtest.adapter.out.persistence

import com.refinvest.core.backtest.domain.valueobject.StrategyVersionId
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JpaBacktestRunReaderAdapterTest {
    @Test
    fun `requests the first completed run for the strategy version`() {
        var requestedStrategyVersionId: Long? = null
        var requestedStatus: BacktestRunStatusJpa? = null
        val adapter = JpaBacktestRunReaderAdapter(object : BacktestRunJpaReader {
            override fun findById(id: Long): BacktestRunJpaEntity? = null

            override fun findFirstByStrategyVersionIdAndStatusOrderByCreatedAtDesc(
                strategyVersionId: Long,
                status: BacktestRunStatusJpa,
            ): BacktestRunJpaEntity? {
                requestedStrategyVersionId = strategyVersionId
                requestedStatus = status
                return entity(id = 12L, strategyVersionId = strategyVersionId)
            }

            override fun findAllByStrategyIdOrderByCreatedAtDesc(
                strategyId: Long,
                pageable: Pageable,
            ): Page<BacktestRunJpaEntity> = error("findAllByStrategyId is not used")

            override fun countByMemberIdAndCreatedAtBetween(
                memberId: Long,
                startInclusive: Instant,
                endExclusive: Instant,
            ): Long = error("countByMemberIdAndCreatedAtBetween is not used")
        })

        val result = adapter.findLatestCompletedByStrategyVersionId(StrategyVersionId(20L))

        assertEquals(20L, requestedStrategyVersionId)
        assertEquals(BacktestRunStatusJpa.COMPLETED, requestedStatus)
        assertEquals(12L, result?.id?.value)
        assertEquals("COMPLETED", result?.status?.name)
    }

    @Test
    fun `returns null when no completed run is found`() {
        val adapter = JpaBacktestRunReaderAdapter(object : BacktestRunJpaReader {
            override fun findById(id: Long): BacktestRunJpaEntity? = null

            override fun findFirstByStrategyVersionIdAndStatusOrderByCreatedAtDesc(
                strategyVersionId: Long,
                status: BacktestRunStatusJpa,
            ): BacktestRunJpaEntity? = null

            override fun findAllByStrategyIdOrderByCreatedAtDesc(
                strategyId: Long,
                pageable: Pageable,
            ): Page<BacktestRunJpaEntity> = error("findAllByStrategyId is not used")

            override fun countByMemberIdAndCreatedAtBetween(
                memberId: Long,
                startInclusive: Instant,
                endExclusive: Instant,
            ): Long = error("countByMemberIdAndCreatedAtBetween is not used")
        })

        assertNull(adapter.findLatestCompletedByStrategyVersionId(StrategyVersionId(20L)))
    }

    private fun entity(id: Long, strategyVersionId: Long): BacktestRunJpaEntity = BacktestRunJpaEntity(
        id = id,
        strategyId = 30L,
        strategyVersionId = strategyVersionId,
        requestedPeriodStart = LocalDate.of(2024, 1, 1),
        requestedPeriodEnd = LocalDate.of(2024, 12, 31),
        commission = BigDecimal("0.001"),
        slippage = BigDecimal("0.002"),
        status = BacktestRunStatusJpa.COMPLETED,
        createdAt = Instant.parse("2024-03-01T00:00:00Z"),
    )
}
