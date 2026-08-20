package com.refinvest.core.backtest.application.backtest.execution

import com.refinvest.core.backtest.domain.BacktestRun
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
import com.refinvest.core.backtest.domain.valueobject.StrategyId
import com.refinvest.core.backtest.domain.valueobject.StrategyVersionId
import com.refinvest.core.backtest.port.inbound.backtest.execution.CompleteBacktestRunCommand
import com.refinvest.core.backtest.port.inbound.backtest.execution.FailBacktestRunCommand
import com.refinvest.core.backtest.port.inbound.backtest.execution.StartBacktestRunCommand
import com.refinvest.core.backtest.port.outbound.BacktestRunStore
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RecordBacktestRunExecutionServiceTest {
    @Test
    fun `records a completed execution and persists its result`() {
        val store = FakeBacktestRunStore(pendingRun())
        val service = RecordBacktestRunExecutionService(store)

        service.execute(startCommand())
        val result = resultFor(BacktestRunId(10L))
        val returnedId = service.execute(CompleteBacktestRunCommand(BacktestRunId(10L), result))

        assertEquals(BacktestRunId(10L), returnedId)
        assertEquals(BacktestRunStatus.COMPLETED, store.run.status)
        assertEquals(result, store.run.result)
        assertEquals(DatasetSnapshotId("snapshot-1"), store.run.datasetSnapshotId)
    }

    @Test
    fun `records a failed execution after it starts`() {
        val store = FakeBacktestRunStore(pendingRun())
        val service = RecordBacktestRunExecutionService(store)

        service.execute(startCommand())
        service.execute(FailBacktestRunCommand(BacktestRunId(10L), "PRICE_DATA_MISSING"))

        assertEquals(BacktestRunStatus.FAILED, store.run.status)
        assertEquals("PRICE_DATA_MISSING", store.run.failureReason)
    }

    @Test
    fun `rejects completion before an execution starts`() {
        val service = RecordBacktestRunExecutionService(FakeBacktestRunStore(pendingRun()))

        assertFailsWith<IllegalArgumentException> {
            service.execute(CompleteBacktestRunCommand(BacktestRunId(10L), resultFor(BacktestRunId(10L))))
        }
    }

    @Test
    fun `rejects an unknown run`() {
        val service = RecordBacktestRunExecutionService(BacktestRunStore { error("must not save") })

        assertFailsWith<IllegalArgumentException> { service.execute(startCommand()) }
    }

    private fun startCommand() = StartBacktestRunCommand(
        backtestRunId = BacktestRunId(10L),
        actualPeriod = Period(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31)),
        datasetSnapshotId = DatasetSnapshotId("snapshot-1"),
        engineVersion = EngineVersion("engine-1"),
    )

    private fun pendingRun(): BacktestRun = BacktestRun.createPending(
        id = BacktestRunId(10L),
        strategyId = StrategyId(7L),
        strategyVersionId = StrategyVersionId(42L),
        requestedPeriod = Period(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31)),
        feeModel = FeeModel(Percent(BigDecimal("0.001")), Percent(BigDecimal("0.002"))),
        createdAt = Instant.parse("2025-01-01T00:00:00Z"),
    )

    private fun resultFor(runId: BacktestRunId): BacktestResult = BacktestResult(
        backtestRunId = runId,
        metrics = BacktestResultMetrics(
            BigDecimal("0.10"), BigDecimal("0.08"), BigDecimal("0.03"), BigDecimal("1.20"), BigDecimal("0.60"),
            3, BigDecimal("0.04"), BigDecimal("5"), BigDecimal("1.50"),
        ),
        equityCurve = emptyList(),
        trades = emptyList(),
        benchmark = Benchmark(BuyAndHoldResult(BigDecimal("0.05"), BigDecimal("0.04"), BigDecimal("0.02")), null),
        signalExecutionDelay = SignalExecutionDelay(BigDecimal.ONE, BigDecimal.ONE, emptyList()),
        sampleSizeWarning = SampleSizeWarning.LOW,
        dataIntegrityStatus = DataIntegrityStatus(DatasetSnapshotId("snapshot-1"), true, true),
    )

    private class FakeBacktestRunStore(
        var run: BacktestRun,
    ) : BacktestRunStore {
        override fun save(backtestRun: BacktestRun) {
            run = backtestRun
        }

        override fun findById(id: BacktestRunId): BacktestRun? = run.takeIf { it.id == id }
    }
}
