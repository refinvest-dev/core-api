package com.refinvest.core.backtest.domain

import com.refinvest.core.backtest.domain.backtest.BacktestResult
import com.refinvest.core.backtest.domain.backtest.BacktestResultMetrics
import com.refinvest.core.backtest.domain.backtest.Benchmark
import com.refinvest.core.backtest.domain.backtest.BuyAndHoldResult
import com.refinvest.core.backtest.domain.backtest.DataIntegrityStatus
import com.refinvest.core.backtest.domain.backtest.SampleSizeWarning
import com.refinvest.core.backtest.domain.backtest.SignalExecutionDelay
import com.refinvest.core.backtest.domain.backtest.SignalExecutionMarketRelation
import com.refinvest.core.backtest.domain.valueobject.BacktestRunId
import com.refinvest.core.backtest.domain.valueobject.BacktestRunStatus
import com.refinvest.core.backtest.domain.valueobject.DatasetSnapshotId
import com.refinvest.core.backtest.domain.valueobject.EngineVersion
import com.refinvest.core.backtest.domain.valueobject.FeeModel
import com.refinvest.core.backtest.domain.valueobject.Percent
import com.refinvest.core.backtest.domain.valueobject.Period
import com.refinvest.core.backtest.domain.valueobject.StrategyVersionId
import com.refinvest.core.backtest.domain.valueobject.StrategyId
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertFails
import com.refinvest.core.backtest.domain.backtest.EquityCurvePoint

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

    @Test
    fun `preserves undefined metrics for a zero trade result`() {
        val run = pendingRun()
        val snapshotId = DatasetSnapshotId("snapshot-1")
        run.start(period(), snapshotId, EngineVersion("engine-1"))

        run.complete(resultFor(run, snapshotId))

        assertNull(run.result!!.metrics.sharpe)
        assertNull(run.result!!.metrics.profitFactor)
    }

    @Test
    fun `allows a pending run to fail without Compute execution metadata when submission is rejected`() {
        val run = pendingRun()

        run.failWithoutExecution("DSL_INVALID")

        assertEquals(BacktestRunStatus.FAILED, run.status)
        assertNull(run.actualPeriod)
        assertNull(run.datasetSnapshotId)
        assertNull(run.engineVersion)
    }

    @Test
    fun `preserves a structured Compute failure code`() {
        val run = pendingRun()
        run.start(period(), DatasetSnapshotId("snapshot-1"), EngineVersion("engine-1"))

        run.fail("Price data is missing for the requested period.", "PRICE_DATA_MISSING")

        assertEquals(BacktestRunStatus.FAILED, run.status)
        assertEquals("PRICE_DATA_MISSING", run.errorCode)
    }

    @Test
    fun restorationRejectsAResultFromAnotherRunOrSnapshot() {
        val run = pendingRun()
        val snapshot = DatasetSnapshotId("snapshot-1")
        run.start(period(), snapshot, EngineVersion("engine-1"))
        run.complete(resultFor(run, snapshot))
        val other = BacktestRun.createPending(BacktestRunId(999), run.strategyId, run.strategyVersionId,
            run.requestedPeriod, run.feeModel, run.createdAt)
        listOf(resultFor(other, snapshot), resultFor(run, DatasetSnapshotId("other-snapshot"))).forEach { result ->
            assertFailsWith<IllegalArgumentException> { restoreCompleted(run, result) }
        }
        assertEquals(BacktestRunStatus.COMPLETED, restoreCompleted(run, run.result!!).status)
    }

    @Test
    fun rejectedFailureDoesNotLeaveTheAggregateInAnInvalidState() {
        val pending = pendingRun()
        assertFailsWith<IllegalArgumentException> { pending.failWithoutExecution("failure", " ") }
        assertEquals(BacktestRunStatus.PENDING, pending.status)
        assertNull(pending.failureReason)
        val running = pendingRun().apply { start(period(), DatasetSnapshotId("snapshot-1"), EngineVersion("engine-1")) }
        assertFailsWith<IllegalArgumentException> { running.fail("failure", " ") }
        assertEquals(BacktestRunStatus.RUNNING, running.status)
        assertNull(running.errorCode)
    }

    @Test
    fun completedResultOwnsImmutableCopiesOfAllItsNestedCollections() {
        val original = resultFor(pendingRun(), DatasetSnapshotId("snapshot-1"))
        val curve = mutableListOf(EquityCurvePoint(LocalDate.parse("2025-01-01"), BigDecimal.ONE),
            EquityCurvePoint(LocalDate.parse("2025-01-02"), BigDecimal("1.1")))
        val distribution = mutableListOf(BigDecimal.ONE, BigDecimal.TEN)
        val result = BacktestResult(original.backtestRunId, original.metrics, curve, original.trades,
            Benchmark(BuyAndHoldResult("QQQ", curve, BigDecimal.ZERO, null, BigDecimal.ZERO), null),
            original.signalExecutionMarketRelation, SignalExecutionDelay(BigDecimal.ONE, BigDecimal.TEN, distribution),
            original.sampleSizeWarning, original.dataIntegrityStatus)
        curve.clear(); distribution.clear()
        assertEquals(2, result.equityCurve.size)
        assertEquals(2, result.benchmark.primary.equityCurve.size)
        assertEquals(2, result.signalExecutionDelay.distribution.size)
        assertFails { (result.equityCurve as MutableList<EquityCurvePoint>).clear() }
        assertFails { (result.benchmark.primary.equityCurve as MutableList<EquityCurvePoint>).clear() }
        assertFails { (result.signalExecutionDelay.distribution as MutableList<BigDecimal>).clear() }
        assertEquals(2, result.benchmark.primary.equityCurve.size)
    }

    private fun restoreCompleted(run: BacktestRun, result: BacktestResult) = BacktestRun.restore(
        run.id, run.strategyId, run.strategyVersionId, run.requestedPeriod, run.feeModel, run.createdAt,
        BacktestRunStatus.COMPLETED, run.actualPeriod, run.datasetSnapshotId, run.engineVersion, result, null)

    private fun pendingRun(): BacktestRun = BacktestRun.createPending(
        id = BacktestRunId(1L),
        strategyId = StrategyId(3L),
        strategyVersionId = StrategyVersionId(2L),
        requestedPeriod = period(),
        feeModel = FeeModel(Percent(BigDecimal.ZERO), Percent(BigDecimal.ZERO)),
        createdAt = Instant.parse("2026-08-11T00:00:00Z"),
    )

    private fun period(): Period = Period(LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"))

    private fun resultFor(run: BacktestRun, snapshotId: DatasetSnapshotId): BacktestResult = BacktestResult(
        backtestRunId = run.id,
        metrics = BacktestResultMetrics(
            totalReturn = null,
            cagr = null,
            mdd = null,
            sharpe = null,
            winRate = null,
            tradeCount = 0,
            avgTradeReturn = null,
            avgHoldingPeriod = null,
            profitFactor = null,
        ),
        equityCurve = emptyList(),
        trades = emptyList(),
        benchmark = Benchmark(BuyAndHoldResult("QQQ", emptyList(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), null),
        signalExecutionMarketRelation = SignalExecutionMarketRelation.SAME_MARKET,
        signalExecutionDelay = SignalExecutionDelay(BigDecimal.ZERO, BigDecimal.ZERO, emptyList()),
        sampleSizeWarning = SampleSizeWarning.ZERO,
        dataIntegrityStatus = DataIntegrityStatus(snapshotId, false, true),
    )
}
