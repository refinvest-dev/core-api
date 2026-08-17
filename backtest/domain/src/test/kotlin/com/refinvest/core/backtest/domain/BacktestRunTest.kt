package com.refinvest.core.backtest.domain

import com.refinvest.core.backtest.domain.backtest.BacktestResult
import com.refinvest.core.backtest.domain.backtest.BacktestResultMetrics
import com.refinvest.core.backtest.domain.backtest.Benchmark
import com.refinvest.core.backtest.domain.backtest.BuyAndHoldResult
import com.refinvest.core.backtest.domain.backtest.DataIntegrityStatus
import com.refinvest.core.backtest.domain.backtest.SampleSizeWarning
import com.refinvest.core.backtest.domain.backtest.SignalExecutionDelay
import com.refinvest.core.backtest.domain.valueobject.BacktestRunId
import com.refinvest.core.backtest.domain.valueobject.BacktestRunStatus
import com.refinvest.core.backtest.domain.valueobject.DatasetSnapshotId
import com.refinvest.core.backtest.domain.valueobject.EngineVersion
import com.refinvest.core.backtest.domain.valueobject.FeeModel
import com.refinvest.core.backtest.domain.valueobject.Percent
import com.refinvest.core.backtest.domain.valueobject.Period
import com.refinvest.core.backtest.domain.valueobject.StrategyVersionId
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BacktestRunTest {
    @Test
    fun `creates a pending run without Compute execution metadata`() {
        val run = pendingRun()

        assertEquals(BacktestRunStatus.PENDING, run.status)
        assertNull(run.datasetSnapshotId)
        assertNull(run.engineVersion)
        assertNull(run.actualPeriod)
    }

    @Test
    fun `allows only the forward state transition from pending to running`() {
        val run = pendingRun()

        run.start(period(), DatasetSnapshotId("snapshot-1"), EngineVersion("engine-1"))

        assertEquals(BacktestRunStatus.RUNNING, run.status)
        assertFailsWith<IllegalArgumentException> {
            run.start(period(), DatasetSnapshotId("snapshot-2"), EngineVersion("engine-2"))
        }
    }

    @Test
    fun `rejects completion before Compute begins`() {
        val run = pendingRun()

        assertFailsWith<IllegalArgumentException> { run.complete(resultFor(run, DatasetSnapshotId("snapshot-1"))) }
    }

    @Test
    fun `requires a failure reason when failing`() {
        val run = pendingRun()
        run.start(period(), DatasetSnapshotId("snapshot-1"), EngineVersion("engine-1"))

        assertFailsWith<IllegalArgumentException> { run.fail(" ") }
    }

    private fun pendingRun(): BacktestRun = BacktestRun.createPending(
        id = BacktestRunId(1L),
        strategyVersionId = StrategyVersionId(2L),
        requestedPeriod = period(),
        feeModel = FeeModel(Percent(BigDecimal.ZERO), Percent(BigDecimal.ZERO)),
        createdAt = Instant.parse("2026-08-11T00:00:00Z"),
    )

    private fun period(): Period = Period(LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"))

    private fun resultFor(run: BacktestRun, snapshotId: DatasetSnapshotId): BacktestResult = BacktestResult(
        backtestRunId = run.id,
        metrics = BacktestResultMetrics(
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        ),
        equityCurve = emptyList(),
        trades = emptyList(),
        benchmark = Benchmark(BuyAndHoldResult(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), null),
        signalExecutionDelay = SignalExecutionDelay(BigDecimal.ZERO, BigDecimal.ZERO, emptyList()),
        sampleSizeWarning = SampleSizeWarning.ZERO,
        dataIntegrityStatus = DataIntegrityStatus(snapshotId, false, true),
    )
}
