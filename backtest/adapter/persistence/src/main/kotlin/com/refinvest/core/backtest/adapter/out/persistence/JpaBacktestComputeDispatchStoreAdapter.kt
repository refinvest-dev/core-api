package com.refinvest.core.backtest.adapter.out.persistence

import com.refinvest.core.backtest.domain.valueobject.BacktestRunId
import com.refinvest.core.backtest.port.outbound.compute.ComputeIdempotencyKey
import com.refinvest.core.backtest.port.outbound.persistence.dispatch.BacktestComputeDispatchStore
import com.refinvest.core.backtest.port.outbound.persistence.dispatch.ClaimedBacktestComputeDispatch
import com.refinvest.core.backtest.port.outbound.persistence.dispatch.ClaimedSubmittedBacktestComputeDispatch
import com.refinvest.core.backtest.port.outbound.persistence.dispatch.PendingBacktestComputeDispatch
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Repository
class JpaBacktestComputeDispatchStoreAdapter(
    private val dispatchJpaStore: BacktestComputeDispatchJpaStore,
    private val meterRegistry: MeterRegistry,
) : BacktestComputeDispatchStore {
    override fun enqueue(dispatch: PendingBacktestComputeDispatch) {
        dispatchJpaStore.save(
            BacktestComputeDispatchJpaEntity(
                backtestRunId = dispatch.backtestRunId.value,
                idempotencyKey = dispatch.idempotencyKey.value,
                status = BacktestComputeDispatchStatusJpa.PENDING,
                nextAttemptAt = dispatch.createdAt,
                createdAt = dispatch.createdAt,
                updatedAt = dispatch.createdAt,
            ),
        )
        afterCommit {
            meterRegistry.counter("refinvest.backtest.submissions").increment()
            logger.info("event=backtest_submitted runId={}", dispatch.backtestRunId.value)
        }
    }

    @Transactional
    override fun claimNext(now: Instant, leaseDuration: Duration): ClaimedBacktestComputeDispatch? {
        val dispatch = dispatchJpaStore.findClaimable(
            now,
            BacktestComputeDispatchStatusJpa.PENDING,
            PageRequest.of(0, 1),
        ).firstOrNull() ?: return null
        val claimToken = UUID.randomUUID()
        val scheduledAt = dispatch.nextAttemptAt
        val retry = dispatch.dispatchAttemptCount > 0
        dispatch.dispatchAttemptCount++
        dispatch.leaseToken = claimToken
        dispatch.leaseExpiresAt = now.plus(leaseDuration)
        dispatch.updatedAt = now
        observeAttempt("dispatch", dispatch.backtestRunId, scheduledAt, Instant.now(), retry)
        return ClaimedBacktestComputeDispatch(
            backtestRunId = BacktestRunId(dispatch.backtestRunId),
            idempotencyKey = ComputeIdempotencyKey(dispatch.idempotencyKey),
            claimToken = claimToken,
        )
    }

    @Transactional
    override fun claimNextSubmitted(
        now: Instant,
        leaseDuration: Duration,
    ): ClaimedSubmittedBacktestComputeDispatch? {
        val dispatch = dispatchJpaStore.findClaimable(
            now,
            BacktestComputeDispatchStatusJpa.SUBMITTED,
            PageRequest.of(0, 1),
        ).firstOrNull() ?: return null
        val claimToken = UUID.randomUUID()
        val scheduledAt = dispatch.nextAttemptAt
        val retry = dispatch.pollRetryPending || dispatch.leaseToken != null
        dispatch.pollRetryPending = false
        dispatch.leaseToken = claimToken
        dispatch.leaseExpiresAt = now.plus(leaseDuration)
        dispatch.updatedAt = now
        observeAttempt("poll", dispatch.backtestRunId, scheduledAt, Instant.now(), retry)
        return ClaimedSubmittedBacktestComputeDispatch(
            backtestRunId = BacktestRunId(dispatch.backtestRunId),
            computeRunId = requireNotNull(dispatch.computeRunId),
            claimToken = claimToken,
        )
    }

    @Transactional
    override fun markAccepted(backtestRunId: BacktestRunId, claimToken: UUID, computeRunId: String) {
        updateClaimed(backtestRunId, claimToken) { dispatch, now ->
            dispatch.status = BacktestComputeDispatchStatusJpa.SUBMITTED
            dispatch.computeRunId = computeRunId
            dispatch.leaseToken = null
            dispatch.leaseExpiresAt = null
            dispatch.updatedAt = now
        }
    }

    @Transactional
    override fun scheduleRetry(backtestRunId: BacktestRunId, claimToken: UUID, nextAttemptAt: Instant) {
        updateClaimed(backtestRunId, claimToken) { dispatch, now ->
            dispatch.leaseToken = null
            dispatch.leaseExpiresAt = null
            dispatch.nextAttemptAt = nextAttemptAt
            dispatch.updatedAt = now
        }
    }

    @Transactional
    override fun markRejected(backtestRunId: BacktestRunId, claimToken: UUID): Boolean {
        val dispatch = dispatchJpaStore.findById(backtestRunId.value).orElse(null) ?: return false
        if (dispatch.status != BacktestComputeDispatchStatusJpa.PENDING || dispatch.leaseToken != claimToken) return false
        dispatch.status = BacktestComputeDispatchStatusJpa.REJECTED
        dispatch.leaseToken = null
        dispatch.leaseExpiresAt = null
        dispatch.updatedAt = Instant.now()
        return true
    }

    @Transactional
    override fun scheduleNextPoll(
        backtestRunId: BacktestRunId,
        claimToken: UUID,
        nextAttemptAt: Instant,
    ) {
        updateSubmitted(backtestRunId, claimToken) { dispatch, now ->
            dispatch.leaseToken = null
            dispatch.leaseExpiresAt = null
            dispatch.nextAttemptAt = nextAttemptAt
            dispatch.pollRetryPending = false
            dispatch.updatedAt = now
        }
    }

    @Transactional
    override fun schedulePollRetry(backtestRunId: BacktestRunId, claimToken: UUID, nextAttemptAt: Instant) {
        updateSubmitted(backtestRunId, claimToken) { dispatch, now ->
            dispatch.leaseToken = null
            dispatch.leaseExpiresAt = null
            dispatch.nextAttemptAt = nextAttemptAt
            dispatch.pollRetryPending = true
            dispatch.updatedAt = now
        }
    }

    @Transactional
    override fun markTerminal(backtestRunId: BacktestRunId, claimToken: UUID) {
        updateSubmitted(backtestRunId, claimToken) { dispatch, now ->
            dispatch.status = BacktestComputeDispatchStatusJpa.TERMINAL
            dispatch.leaseToken = null
            dispatch.leaseExpiresAt = null
            dispatch.updatedAt = now
        }
    }

    private fun updateClaimed(
        backtestRunId: BacktestRunId,
        claimToken: UUID,
        update: (BacktestComputeDispatchJpaEntity, Instant) -> Unit,
    ) {
        val dispatch = dispatchJpaStore.findById(backtestRunId.value).orElse(null) ?: return
        if (dispatch.status == BacktestComputeDispatchStatusJpa.PENDING && dispatch.leaseToken == claimToken) {
            update(dispatch, Instant.now())
        }
    }

    private fun updateSubmitted(
        backtestRunId: BacktestRunId,
        claimToken: UUID,
        update: (BacktestComputeDispatchJpaEntity, Instant) -> Unit,
    ) {
        val dispatch = dispatchJpaStore.findById(backtestRunId.value).orElse(null) ?: return
        if (dispatch.status == BacktestComputeDispatchStatusJpa.SUBMITTED && dispatch.leaseToken == claimToken) {
            update(dispatch, Instant.now())
        }
    }

    private fun observeAttempt(operation: String, runId: Long, scheduledAt: Instant, startedAt: Instant, retry: Boolean) {
        afterCommit {
            Timer.builder("refinvest.core.backtest.scheduler.lag").tag("operation", operation)
                .publishPercentileHistogram().register(meterRegistry)
                .record(Duration.between(scheduledAt, startedAt).coerceAtLeast(Duration.ZERO))
            if (retry) meterRegistry.counter("refinvest.backtest.retries", "operation", operation).increment()
            logger.info("event=backtest_attempt runId={} operation={} retry={}", runId, operation, retry)
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(JpaBacktestComputeDispatchStoreAdapter::class.java)
    }
}
