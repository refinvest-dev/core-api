package com.refinvest.core.backtest.application.list

import com.refinvest.core.backtest.domain.valueobject.BacktestRunId
import com.refinvest.core.backtest.domain.valueobject.BacktestRunStatus
import com.refinvest.core.backtest.domain.valueobject.FeeModel
import com.refinvest.core.backtest.domain.valueobject.Percent
import com.refinvest.core.backtest.domain.valueobject.Period
import com.refinvest.core.backtest.domain.valueobject.StrategyId
import com.refinvest.core.backtest.domain.valueobject.StrategyVersionId
import com.refinvest.core.backtest.port.inbound.list.ListBacktestRunsQuery
import com.refinvest.core.backtest.port.outbound.persistence.BacktestRunPageReadModel
import com.refinvest.core.backtest.port.outbound.persistence.BacktestRunReadModel
import com.refinvest.core.backtest.port.outbound.persistence.BacktestRunReader
import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.strategy.port.inbound.owner.LookupStrategyOwnerResult
import com.refinvest.core.backtest.port.outbound.member.BacktestMemberIdProvider
import com.refinvest.core.strategy.port.inbound.owner.LookupStrategyOwnerUseCase
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class ListBacktestRunsServiceTest {
    @Test
    fun `returns the requested strategy page`() {
        val strategyId = StrategyId(7L)
        val service = ListBacktestRunsService(object : BacktestRunReader {
            override fun findById(id: BacktestRunId): BacktestRunReadModel? = null

            override fun findByStrategyId(
                strategyId: StrategyId,
                page: Int,
                size: Int,
            ): BacktestRunPageReadModel = BacktestRunPageReadModel(listOf(readModel(strategyId)), 3)

            override fun countByMemberIdAndCreatedAtBetween(
                memberId: MemberId,
                startInclusive: Instant,
                endExclusive: Instant,
            ) = error("countByMemberIdAndCreatedAtBetween is not used by ListBacktestRuns")
        }, existingStrategy(), BacktestMemberIdProvider { MemberId(1) })

        val result = service.execute(ListBacktestRunsQuery(strategyId, page = 1, size = 2))

        assertEquals(1, result.page)
        assertEquals(2, result.size)
        assertEquals(3, result.total)
        assertEquals(BacktestRunId(10L), result.items.single().id)
        assertEquals(strategyId, result.items.single().strategyId)
    }

    @Test
    fun `rejects missing and foreign strategies before reading their history`() {
        listOf(null, LookupStrategyOwnerResult(MemberId(2))).forEach { owner ->
            val service = ListBacktestRunsService(
                backtestRunReader = object : BacktestRunReader {
                    override fun findById(id: BacktestRunId): BacktestRunReadModel? = null

                    override fun findByStrategyId(
                        strategyId: StrategyId,
                        page: Int,
                        size: Int,
                    ) = error("findByStrategyId must not be called for a missing strategy")

                    override fun countByMemberIdAndCreatedAtBetween(
                        memberId: MemberId,
                        startInclusive: Instant,
                        endExclusive: Instant,
                    ) = error("countByMemberIdAndCreatedAtBetween is not used by ListBacktestRuns")
                },
                lookupStrategyOwnerUseCase = LookupStrategyOwnerUseCase { owner },
                backtestMemberIdProvider = BacktestMemberIdProvider { MemberId(1) },
            )

            kotlin.test.assertFailsWith<NoSuchElementException> {
                service.execute(ListBacktestRunsQuery(StrategyId(7L)))
            }
        }
    }

    private fun readModel(strategyId: StrategyId) = BacktestRunReadModel(
        id = BacktestRunId(10L),
        strategyId = strategyId,
        strategyVersionId = StrategyVersionId(20L),
        requestedPeriod = Period(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31)),
        feeModel = FeeModel(Percent(BigDecimal("0.001")), Percent(BigDecimal("0.002"))),
        status = BacktestRunStatus.COMPLETED,
        createdAt = Instant.parse("2024-02-01T00:00:00Z"),
    )

    private fun existingStrategy(): LookupStrategyOwnerUseCase = LookupStrategyOwnerUseCase { query ->
        assertEquals(7L, query.strategyId)
        LookupStrategyOwnerResult(MemberId(1))
    }
}
