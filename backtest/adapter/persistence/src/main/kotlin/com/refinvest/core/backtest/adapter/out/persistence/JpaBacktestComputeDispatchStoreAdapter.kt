package com.refinvest.core.backtest.adapter.out.persistence

import com.refinvest.core.backtest.domain.valueobject.BacktestRunId
import com.refinvest.core.backtest.port.outbound.compute.ComputeIdempotencyKey
import com.refinvest.core.backtest.port.outbound.persistence.dispatch.BacktestComputeDispatchStore
import com.refinvest.core.backtest.port.outbound.persistence.dispatch.ClaimedBacktestComputeDispatch
import com.refinvest.core.backtest.port.outbound.persistence.dispatch.PendingBacktestComputeDispatch
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Repository
class JpaBacktestComputeDispatchStoreAdapter(
    private val dispatchJpaStore: BacktestComputeDispatchJpaStore,
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
    }

    @Transactional
    override fun claimNext(now: Instant, leaseDuration: Duration): ClaimedBacktestComputeDispatch? {
        val dispatch = dispatchJpaStore.findClaimable(
            now,
            BacktestComputeDispatchStatusJpa.PENDING,
            PageRequest.of(0, 1),
        ).firstOrNull() ?: return null
        val claimToken = UUID.randomUUID()
        dispatch.leaseToken = claimToken
        dispatch.leaseExpiresAt = now.plus(leaseDuration)
        dispatch.updatedAt = now
        return ClaimedBacktestComputeDispatch(
            backtestRunId = BacktestRunId(dispatch.backtestRunId),
            idempotencyKey = ComputeIdempotencyKey(dispatch.idempotencyKey),
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
}
