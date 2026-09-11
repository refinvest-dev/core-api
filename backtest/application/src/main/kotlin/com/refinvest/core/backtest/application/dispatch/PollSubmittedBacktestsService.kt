package com.refinvest.core.backtest.application.dispatch

import com.refinvest.core.backtest.domain.valueobject.BacktestRunStatus
import com.refinvest.core.backtest.port.inbound.dispatch.PollSubmittedBacktestsUseCase
import com.refinvest.core.backtest.port.inbound.execution.CompleteBacktestRunCommand
import com.refinvest.core.backtest.port.inbound.execution.FailBacktestRunCommand
import com.refinvest.core.backtest.port.inbound.execution.FailBacktestRunWithoutExecutionCommand
import com.refinvest.core.backtest.port.inbound.execution.RecordBacktestRunExecutionUseCase
import com.refinvest.core.backtest.port.inbound.execution.StartBacktestRunCommand
import com.refinvest.core.backtest.port.outbound.compute.ComputeBacktestStatus
import com.refinvest.core.backtest.port.outbound.compute.ComputeBacktestStatusClient
import com.refinvest.core.backtest.port.outbound.compute.ComputeBacktestStatusLookup
import com.refinvest.core.backtest.port.outbound.compute.ComputeBacktestStatusValue
import com.refinvest.core.backtest.port.outbound.observability.BacktestFailureObserver
import com.refinvest.core.backtest.port.outbound.observability.ComputeBacktestFailureObservation
import com.refinvest.core.backtest.port.outbound.persistence.BacktestRunStore
import com.refinvest.core.backtest.port.outbound.persistence.dispatch.BacktestComputeDispatchStore
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration

@Service
open class PollSubmittedBacktestsService(
    private val backtestComputeDispatchStore: BacktestComputeDispatchStore,
    private val backtestRunStore: BacktestRunStore,
    private val recordBacktestRunExecutionUseCase: RecordBacktestRunExecutionUseCase,
    private val computeBacktestStatusClient: ComputeBacktestStatusClient,
    private val backtestFailureObserver: BacktestFailureObserver,
    private val transactionTemplate: TransactionTemplate,
    private val clock: Clock,
) : PollSubmittedBacktestsUseCase {
    override fun execute(): Boolean {
        val dispatch = transactionTemplate.execute {
            backtestComputeDispatchStore.claimNextSubmitted(clock.instant(), POLL_LEASE_DURATION)
        } ?: return false

        val lookup = computeBacktestStatusClient.getBacktestStatus(
            computeRunId = dispatch.computeRunId,
            backtestRunId = dispatch.backtestRunId,
        )
        val failureObservation = transactionTemplate.execute {
            applyLookup(dispatch, lookup)
        }
        failureObservation?.let(backtestFailureObserver::recordComputeFailure)
        return true
    }

    private fun applyLookup(
        dispatch: com.refinvest.core.backtest.port.outbound.persistence.dispatch.ClaimedSubmittedBacktestComputeDispatch,
        lookup: ComputeBacktestStatusLookup,
    ): ComputeBacktestFailureObservation? {
        return when (lookup) {
            is ComputeBacktestStatusLookup.RetryLater -> {
                backtestComputeDispatchStore.scheduleNextPoll(
                    dispatch.backtestRunId,
                    dispatch.claimToken,
                    clock.instant().plus(lookup.retryAfter),
                )
                null
            }
            ComputeBacktestStatusLookup.NotFound -> {
                failForUnavailableRuntime(dispatch)
                null
            }
            is ComputeBacktestStatusLookup.Found -> applyStatus(dispatch, lookup.status)
        }
    }

    private fun applyStatus(
        dispatch: com.refinvest.core.backtest.port.outbound.persistence.dispatch.ClaimedSubmittedBacktestComputeDispatch,
        computeStatus: ComputeBacktestStatus,
    ): ComputeBacktestFailureObservation? {
        return when (computeStatus.status) {
            ComputeBacktestStatusValue.PENDING -> {
                scheduleNextPoll(dispatch)
                null
            }
            ComputeBacktestStatusValue.RUNNING -> {
                if (computeStatus.hasExecutionMetadata()) {
                    startIfPending(dispatch.backtestRunId, computeStatus)
                }
                scheduleNextPoll(dispatch)
                null
            }
            ComputeBacktestStatusValue.COMPLETED -> {
                complete(dispatch.backtestRunId, computeStatus)
                backtestComputeDispatchStore.markTerminal(dispatch.backtestRunId, dispatch.claimToken)
                null
            }
            ComputeBacktestStatusValue.FAILED -> {
                val failureObservation = fail(dispatch.backtestRunId, computeStatus)
                backtestComputeDispatchStore.markTerminal(dispatch.backtestRunId, dispatch.claimToken)
                failureObservation
            }
        }
    }

    private fun startIfPending(backtestRunId: com.refinvest.core.backtest.domain.valueobject.BacktestRunId, status: ComputeBacktestStatus) {
        if (backtestRunStore.findById(backtestRunId)?.status != BacktestRunStatus.PENDING) return
        val metadata = status.requireExecutionMetadata()
        recordBacktestRunExecutionUseCase.execute(
            StartBacktestRunCommand(backtestRunId, metadata.actualPeriod, metadata.datasetSnapshotId, metadata.engineVersion),
        )
    }

    private fun complete(backtestRunId: com.refinvest.core.backtest.domain.valueobject.BacktestRunId, status: ComputeBacktestStatus) {
        startIfPending(backtestRunId, status)
        if (backtestRunStore.findById(backtestRunId)?.status == BacktestRunStatus.RUNNING) {
            recordBacktestRunExecutionUseCase.execute(
                CompleteBacktestRunCommand(
                    backtestRunId,
                    requireNotNull(status.result) { "COMPLETED Compute status requires a result" },
                ),
            )
        }
    }

    private fun fail(
        backtestRunId: com.refinvest.core.backtest.domain.valueobject.BacktestRunId,
        status: ComputeBacktestStatus,
    ): ComputeBacktestFailureObservation? {
        val backtestRun = backtestRunStore.findById(backtestRunId) ?: return null
        val failureReason = requireNotNull(status.failureReason) { "FAILED Compute status requires a failureReason" }
        return when (backtestRun.status) {
            BacktestRunStatus.PENDING -> {
                if (status.actualPeriod == null || status.datasetSnapshotId == null || status.engineVersion == null) {
                    recordBacktestRunExecutionUseCase.execute(
                        FailBacktestRunWithoutExecutionCommand(backtestRunId, failureReason, status.errorCode),
                    )
                } else {
                    startIfPending(backtestRunId, status)
                    recordBacktestRunExecutionUseCase.execute(FailBacktestRunCommand(backtestRunId, failureReason, status.errorCode))
                }
                backtestRun.toFailureObservation(status.errorCode)
            }
            BacktestRunStatus.RUNNING -> {
                recordBacktestRunExecutionUseCase.execute(
                    FailBacktestRunCommand(backtestRunId, failureReason, status.errorCode),
                )
                backtestRun.toFailureObservation(status.errorCode)
            }
            BacktestRunStatus.COMPLETED, BacktestRunStatus.FAILED -> null
        }
    }

    private fun com.refinvest.core.backtest.domain.BacktestRun.toFailureObservation(errorCode: String?) =
        ComputeBacktestFailureObservation(
            backtestRunId = id,
            strategyId = strategyId,
            strategyVersionId = strategyVersionId,
            errorCode = errorCode,
        )

    private fun failForUnavailableRuntime(
        dispatch: com.refinvest.core.backtest.port.outbound.persistence.dispatch.ClaimedSubmittedBacktestComputeDispatch,
    ) {
        when (backtestRunStore.findById(dispatch.backtestRunId)?.status) {
            BacktestRunStatus.PENDING -> recordBacktestRunExecutionUseCase.execute(
                FailBacktestRunWithoutExecutionCommand(dispatch.backtestRunId, COMPUTE_RUNTIME_STATUS_UNAVAILABLE),
            )
            BacktestRunStatus.RUNNING -> recordBacktestRunExecutionUseCase.execute(
                FailBacktestRunCommand(dispatch.backtestRunId, COMPUTE_RUNTIME_STATUS_UNAVAILABLE),
            )
            BacktestRunStatus.COMPLETED, BacktestRunStatus.FAILED, null -> Unit
        }
        backtestComputeDispatchStore.markTerminal(dispatch.backtestRunId, dispatch.claimToken)
    }

    private fun scheduleNextPoll(
        dispatch: com.refinvest.core.backtest.port.outbound.persistence.dispatch.ClaimedSubmittedBacktestComputeDispatch,
    ) {
        backtestComputeDispatchStore.scheduleNextPoll(
            dispatch.backtestRunId,
            dispatch.claimToken,
            clock.instant().plus(POLL_INTERVAL),
        )
    }

    private companion object {
        val POLL_LEASE_DURATION: Duration = Duration.ofMinutes(1)
        val POLL_INTERVAL: Duration = Duration.ofSeconds(5)
        const val COMPUTE_RUNTIME_STATUS_UNAVAILABLE = "COMPUTE_RUNTIME_STATUS_UNAVAILABLE"
    }
}
