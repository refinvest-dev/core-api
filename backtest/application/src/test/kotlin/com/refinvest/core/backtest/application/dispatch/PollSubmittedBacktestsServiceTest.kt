package com.refinvest.core.backtest.application.dispatch

import com.refinvest.core.backtest.domain.BacktestRun
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
import com.refinvest.core.backtest.domain.valueobject.StrategyId
import com.refinvest.core.backtest.domain.valueobject.StrategyVersionId
import com.refinvest.core.backtest.port.inbound.execution.CompleteBacktestRunCommand
import com.refinvest.core.backtest.port.inbound.execution.FailBacktestRunCommand
import com.refinvest.core.backtest.port.inbound.execution.FailBacktestRunWithoutExecutionCommand
import com.refinvest.core.backtest.port.inbound.execution.RecordBacktestRunExecutionCommand
import com.refinvest.core.backtest.port.inbound.execution.RecordBacktestRunExecutionUseCase
import com.refinvest.core.backtest.port.inbound.execution.StartBacktestRunCommand
import com.refinvest.core.backtest.port.outbound.compute.ComputeBacktestStatus
import com.refinvest.core.backtest.port.outbound.compute.ComputeBacktestStatusClient
import com.refinvest.core.backtest.port.outbound.compute.ComputeBacktestStatusLookup
import com.refinvest.core.backtest.port.outbound.compute.ComputeBacktestStatusValue
import com.refinvest.core.backtest.port.outbound.persistence.BacktestRunStore
import com.refinvest.core.backtest.port.outbound.persistence.dispatch.BacktestComputeDispatchStore
import com.refinvest.core.backtest.port.outbound.persistence.dispatch.ClaimedBacktestComputeDispatch
import com.refinvest.core.backtest.port.outbound.persistence.dispatch.ClaimedSubmittedBacktestComputeDispatch
import com.refinvest.core.backtest.port.outbound.persistence.dispatch.PendingBacktestComputeDispatch
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PollSubmittedBacktestsServiceTest {
    @Test
    fun `completes a pending Core run from a completed Compute status`() {
        val run = pendingRun()
        val dispatchStore = FakeDispatchStore(run.id)
        val service = service(
            run = run,
            dispatchStore = dispatchStore,
            computeStatusLookup = ComputeBacktestStatusLookup.Found(
                ComputeBacktestStatus(
                    status = ComputeBacktestStatusValue.COMPLETED,
                    actualPeriod = period(),
                    datasetSnapshotId = DatasetSnapshotId("snapshot-1"),
                    engineVersion = EngineVersion("engine-1"),
                    result = resultFor(run.id),
                ),
            ),
        )

        assertTrue(service.execute())
        assertEquals(BacktestRunStatus.COMPLETED, run.status)
        assertTrue(dispatchStore.terminal)
    }

    @Test
    fun `fails an unstarted Core run when an accepted Compute job is unavailable`() {
        val run = pendingRun()
        val dispatchStore = FakeDispatchStore(run.id)
        val service = service(run, dispatchStore, ComputeBacktestStatusLookup.NotFound)

        assertTrue(service.execute())
        assertEquals(BacktestRunStatus.FAILED, run.status)
        assertEquals("COMPUTE_RUNTIME_STATUS_UNAVAILABLE", run.failureReason)
        assertTrue(dispatchStore.terminal)
    }

    @Test
    fun `keeps a pending Core run polling while Compute is running without execution metadata`() {
        val run = pendingRun()
        val dispatchStore = FakeDispatchStore(run.id)
        val service = service(
            run = run,
            dispatchStore = dispatchStore,
            computeStatusLookup = ComputeBacktestStatusLookup.Found(
                ComputeBacktestStatus(status = ComputeBacktestStatusValue.RUNNING),
            ),
        )

        assertTrue(service.execute())
        assertEquals(BacktestRunStatus.PENDING, run.status)
        assertTrue(!dispatchStore.terminal)
    }

    @Test
    fun `fails a running Core run from a failed Compute status`() {
        val run = pendingRun().also {
            it.start(period(), DatasetSnapshotId("snapshot-1"), EngineVersion("engine-1"))
        }
        val dispatchStore = FakeDispatchStore(run.id)
        val service = service(
            run = run,
            dispatchStore = dispatchStore,
            computeStatusLookup = ComputeBacktestStatusLookup.Found(
                ComputeBacktestStatus(
                    status = ComputeBacktestStatusValue.FAILED,
                    actualPeriod = period(),
                    datasetSnapshotId = DatasetSnapshotId("snapshot-1"),
                    engineVersion = EngineVersion("engine-1"),
                    failureReason = "Price data is missing for the requested period.",
                    errorCode = "PRICE_DATA_MISSING",
                ),
            ),
        )

        assertTrue(service.execute())
        assertEquals(BacktestRunStatus.FAILED, run.status)
        assertEquals("Price data is missing for the requested period.", run.failureReason)
        assertEquals("PRICE_DATA_MISSING", run.errorCode)
        assertTrue(dispatchStore.terminal)
    }

    private fun service(
        run: BacktestRun,
        dispatchStore: FakeDispatchStore,
        computeStatusLookup: ComputeBacktestStatusLookup,
    ): PollSubmittedBacktestsService {
        val store = object : BacktestRunStore {
            override fun save(backtestRun: BacktestRun) = Unit
            override fun findById(id: BacktestRunId): BacktestRun? = run.takeIf { it.id == id }
        }
        return PollSubmittedBacktestsService(
            backtestComputeDispatchStore = dispatchStore,
            backtestRunStore = store,
            recordBacktestRunExecutionUseCase = executionUseCase(run),
            computeBacktestStatusClient = ComputeBacktestStatusClient { _, _ -> computeStatusLookup },
            transactionTemplate = TransactionTemplate(NoOpTransactionManager()),
            clock = Clock.fixed(Instant.parse("2026-09-03T00:00:00Z"), ZoneOffset.UTC),
        )
    }

    private fun executionUseCase(run: BacktestRun) = RecordBacktestRunExecutionUseCase { command ->
        when (command) {
            is StartBacktestRunCommand -> run.start(command.actualPeriod, command.datasetSnapshotId, command.engineVersion)
            is CompleteBacktestRunCommand -> run.complete(command.result)
            is FailBacktestRunCommand -> run.fail(command.failureReason, command.errorCode)
            is FailBacktestRunWithoutExecutionCommand -> run.failWithoutExecution(command.failureReason, command.errorCode)
        }
        run.id
    }

    private fun pendingRun(): BacktestRun = BacktestRun.createPending(
        id = BacktestRunId(10L),
        strategyId = StrategyId(7L),
        strategyVersionId = StrategyVersionId(42L),
        requestedPeriod = period(),
        feeModel = FeeModel(Percent(BigDecimal.ZERO), Percent(BigDecimal.ZERO)),
        createdAt = Instant.parse("2026-09-01T00:00:00Z"),
    )

    private fun period() = Period(LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"))

    private fun resultFor(runId: BacktestRunId) = BacktestResult(
        backtestRunId = runId,
        metrics = BacktestResultMetrics(
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        ),
        equityCurve = emptyList(),
        trades = emptyList(),
        benchmark = Benchmark(BuyAndHoldResult("QQQ", emptyList(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), null),
        signalExecutionMarketRelation = SignalExecutionMarketRelation.SAME_MARKET,
        signalExecutionDelay = SignalExecutionDelay(BigDecimal.ZERO, BigDecimal.ZERO, emptyList()),
        sampleSizeWarning = SampleSizeWarning.ZERO,
        dataIntegrityStatus = DataIntegrityStatus(DatasetSnapshotId("snapshot-1"), true, true),
    )

    private class FakeDispatchStore(private val backtestRunId: BacktestRunId) : BacktestComputeDispatchStore {
        private var claimed = false
        var terminal = false

        override fun enqueue(dispatch: PendingBacktestComputeDispatch) = Unit
        override fun claimNext(now: Instant, leaseDuration: Duration): ClaimedBacktestComputeDispatch? = null
        override fun claimNextSubmitted(now: Instant, leaseDuration: Duration): ClaimedSubmittedBacktestComputeDispatch? {
            if (claimed) return null
            claimed = true
            return ClaimedSubmittedBacktestComputeDispatch(backtestRunId, "compute-10", UUID.randomUUID())
        }
        override fun markAccepted(backtestRunId: BacktestRunId, claimToken: UUID, computeRunId: String) = Unit
        override fun scheduleRetry(backtestRunId: BacktestRunId, claimToken: UUID, nextAttemptAt: Instant) = Unit
        override fun markRejected(backtestRunId: BacktestRunId, claimToken: UUID) = false
        override fun scheduleNextPoll(backtestRunId: BacktestRunId, claimToken: UUID, nextAttemptAt: Instant) = Unit
        override fun markTerminal(backtestRunId: BacktestRunId, claimToken: UUID) { terminal = true }
    }

    private class NoOpTransactionManager : PlatformTransactionManager {
        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = SimpleTransactionStatus()
        override fun commit(status: TransactionStatus) = Unit
        override fun rollback(status: TransactionStatus) = Unit
    }
}
