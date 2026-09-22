package com.refinvest.core.backtest.port.outbound.persistence.dispatch

import com.refinvest.core.backtest.domain.valueobject.BacktestRunId
import com.refinvest.core.backtest.port.outbound.compute.ComputeIdempotencyKey
import java.time.Duration
import java.time.Instant
import java.util.UUID

interface BacktestComputeDispatchStore {
    fun enqueue(dispatch: PendingBacktestComputeDispatch)

    fun claimNext(now: Instant, leaseDuration: Duration): ClaimedBacktestComputeDispatch?

    fun claimNextSubmitted(now: Instant, leaseDuration: Duration): ClaimedSubmittedBacktestComputeDispatch?

    fun markAccepted(backtestRunId: BacktestRunId, claimToken: UUID, computeRunId: String)

    fun scheduleRetry(backtestRunId: BacktestRunId, claimToken: UUID, nextAttemptAt: Instant)

    fun markRejected(backtestRunId: BacktestRunId, claimToken: UUID): Boolean

    fun scheduleNextPoll(backtestRunId: BacktestRunId, claimToken: UUID, nextAttemptAt: Instant)

    fun schedulePollRetry(backtestRunId: BacktestRunId, claimToken: UUID, nextAttemptAt: Instant) =
        scheduleNextPoll(backtestRunId, claimToken, nextAttemptAt)

    fun markTerminal(backtestRunId: BacktestRunId, claimToken: UUID)
}

data class PendingBacktestComputeDispatch(
    val backtestRunId: BacktestRunId,
    val idempotencyKey: ComputeIdempotencyKey,
    val createdAt: Instant,
)

data class ClaimedBacktestComputeDispatch(
    val backtestRunId: BacktestRunId,
    val idempotencyKey: ComputeIdempotencyKey,
    val claimToken: UUID,
)

data class ClaimedSubmittedBacktestComputeDispatch(
    val backtestRunId: BacktestRunId,
    val computeRunId: String,
    val claimToken: UUID,
)
