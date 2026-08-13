package com.refinvest.core.backtest.domain

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
