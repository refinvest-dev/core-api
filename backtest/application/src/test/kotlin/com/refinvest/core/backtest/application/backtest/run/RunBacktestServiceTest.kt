package com.refinvest.core.backtest.application.backtest.run

import com.refinvest.core.backtest.domain.BacktestRun
import com.refinvest.core.backtest.domain.BacktestRunId
import com.refinvest.core.backtest.domain.BacktestRunStatus
import com.refinvest.core.backtest.domain.FeeModel
import com.refinvest.core.backtest.domain.Percent
import com.refinvest.core.backtest.domain.Period
import com.refinvest.core.backtest.domain.StrategyVersionId
import com.refinvest.core.backtest.port.inbound.backtest.run.RunBacktestCommand
import com.refinvest.core.backtest.port.outbound.BacktestRunIdGenerator
import com.refinvest.core.backtest.port.outbound.BacktestRunStore
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RunBacktestServiceTest {
    @Test
    fun `creates and persists only a pending backtest run`() {
        var saved: BacktestRun? = null
        val id = BacktestRunId(10L)
        val service = RunBacktestService(
            backtestRunStore = BacktestRunStore { saved = it },
            backtestRunIdGenerator = BacktestRunIdGenerator { id },
            clock = Clock.fixed(Instant.parse("2026-08-11T00:00:00Z"), ZoneOffset.UTC),
        )

        val result = service.execute(
            RunBacktestCommand(
                strategyVersionId = StrategyVersionId(20L),
                period = Period(LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31")),
                feeModel = FeeModel(Percent(BigDecimal.ZERO), Percent(BigDecimal.ZERO)),
            ),
        )

        assertEquals(id, result.id)
        assertEquals(BacktestRunStatus.PENDING, saved?.status)
        assertEquals(StrategyVersionId(20L), saved?.strategyVersionId)
        assertNull(saved?.datasetSnapshotId)
        assertNull(saved?.engineVersion)
    }
}
