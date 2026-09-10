package com.refinvest.core.backtest.adapter.web.backtest.list

import com.refinvest.core.backtest.domain.valueobject.BacktestRunId
import com.refinvest.core.backtest.domain.valueobject.BacktestRunStatus
import com.refinvest.core.backtest.domain.valueobject.FeeModel
import com.refinvest.core.backtest.domain.valueobject.Percent
import com.refinvest.core.backtest.domain.valueobject.Period
import com.refinvest.core.backtest.domain.valueobject.StrategyId
import com.refinvest.core.backtest.domain.valueobject.StrategyVersionId
import com.refinvest.core.backtest.port.inbound.list.BacktestRunSummary
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LatestCompletedBacktestRunResponseTest {
    @Test
    fun `returns a null run when no completed run exists`() {
        assertNull(LatestCompletedBacktestRunResponse.from(null).run)
    }

    @Test
    fun `maps the completed run summary`() {
        val response = LatestCompletedBacktestRunResponse.from(summary())

        assertEquals("10", response.run?.id)
        assertEquals("COMPLETED", response.run?.status)
    }

    private fun summary(): BacktestRunSummary = BacktestRunSummary(
        id = BacktestRunId(10L),
        strategyId = StrategyId(30L),
        strategyVersionId = StrategyVersionId(20L),
        requestedPeriod = Period(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31)),
        feeModel = FeeModel(Percent(BigDecimal("0.001")), Percent(BigDecimal("0.002"))),
        status = BacktestRunStatus.COMPLETED,
        createdAt = Instant.parse("2024-03-01T00:00:00Z"),
    )
}
