package com.refinvest.core.backtest.application.backtest.list

import com.refinvest.core.backtest.domain.valueobject.BacktestRunId
import com.refinvest.core.backtest.domain.valueobject.BacktestRunStatus
import com.refinvest.core.backtest.domain.valueobject.FeeModel
import com.refinvest.core.backtest.domain.valueobject.Percent
import com.refinvest.core.backtest.domain.valueobject.Period
import com.refinvest.core.backtest.domain.valueobject.StrategyId
import com.refinvest.core.backtest.domain.valueobject.StrategyVersionId
import com.refinvest.core.backtest.port.inbound.backtest.list.ListBacktestRunsQuery
import com.refinvest.core.backtest.port.outbound.BacktestRunPageReadModel
import com.refinvest.core.backtest.port.outbound.BacktestRunReadModel
import com.refinvest.core.backtest.port.outbound.BacktestRunReader
import com.refinvest.core.strategy.domain.valueobject.StrategyId as StrategyIdInStrategy
import com.refinvest.core.strategy.port.inbound.strategy.get.GetStrategyResult
import com.refinvest.core.strategy.port.inbound.strategy.get.GetStrategyUseCase
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
        }, existingStrategy())

        val result = service.execute(ListBacktestRunsQuery(strategyId, page = 1, size = 2))

        assertEquals(1, result.page)
        assertEquals(2, result.size)
        assertEquals(3, result.total)
        assertEquals(BacktestRunId(10L), result.items.single().id)
        assertEquals(strategyId, result.items.single().strategyId)
    }

    @Test
    fun `rejects a missing strategy`() {
        val service = ListBacktestRunsService(
            backtestRunReader = BacktestRunReader { null },
            getStrategyUseCase = GetStrategyUseCase { null },
        )

        kotlin.test.assertFailsWith<NoSuchElementException> {
            service.execute(ListBacktestRunsQuery(StrategyId(7L)))
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

    private fun existingStrategy(): GetStrategyUseCase = GetStrategyUseCase { query ->
        GetStrategyResult(
            id = StrategyIdInStrategy(query.strategyId.value),
            name = "strategy",
            createdAt = Instant.parse("2024-01-01T00:00:00Z"),
            latestVersionId = null,
            versions = emptyList(),
        )
    }
}
