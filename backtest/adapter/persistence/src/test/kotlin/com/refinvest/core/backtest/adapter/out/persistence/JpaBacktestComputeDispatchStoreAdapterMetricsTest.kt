package com.refinvest.core.backtest.adapter.out.persistence

import com.refinvest.core.backtest.domain.valueobject.BacktestRunId
import com.refinvest.core.backtest.port.outbound.compute.ComputeIdempotencyKey
import com.refinvest.core.backtest.port.outbound.persistence.dispatch.PendingBacktestComputeDispatch
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.mockito.Mockito
import org.springframework.data.domain.PageRequest
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionSynchronizationUtils
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class JpaBacktestComputeDispatchStoreAdapterMetricsTest {
    private val now = Instant.parse("2026-09-22T00:00:00Z")

    @Test
    fun `submission is counted after durable transaction commits`() {
        val fixture = fixture()
        TransactionSynchronizationManager.initSynchronization()
        try {
            fixture.adapter.enqueue(PendingBacktestComputeDispatch(
                BacktestRunId(42L), ComputeIdempotencyKey(UUID.randomUUID()), now,
            ))
            assertEquals(0.0, fixture.registry.find("refinvest.backtest.submissions").counter()?.count() ?: 0.0)
            TransactionSynchronizationUtils.triggerAfterCommit()
            assertEquals(1.0, fixture.registry.get("refinvest.backtest.submissions").counter().count())
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    @Test
    fun `counts only a started dispatch retry and records bounded lag`() {
        val fixture = fixture()
        val first = fixture.adapter.claimNext(now, Duration.ofMinutes(1))!!
        assertEquals(0.0, fixture.registry.find("refinvest.backtest.retries")
            .tag("operation", "dispatch").counter()?.count() ?: 0.0)

        fixture.adapter.scheduleRetry(first.backtestRunId, first.claimToken, now.plusSeconds(10))
        assertEquals(0.0, fixture.registry.find("refinvest.backtest.retries")
            .tag("operation", "dispatch").counter()?.count() ?: 0.0)
        fixture.adapter.claimNext(now.plusSeconds(12), Duration.ofMinutes(1))

        assertEquals(1.0, fixture.registry.get("refinvest.backtest.retries")
            .tag("operation", "dispatch").counter().count())
        assertEquals(2L, fixture.registry.get("refinvest.core.backtest.scheduler.lag")
            .tag("operation", "dispatch").timer().count())
        assertEquals(setOf("operation"), fixture.registry.get("refinvest.backtest.retries")
            .tag("operation", "dispatch").counter().id.tags.map { it.key }.toSet())
    }

    @Test
    fun `routine polls do not count as retries but retry after transient failure does`() {
        val fixture = fixture(BacktestComputeDispatchStatusJpa.SUBMITTED)
        val first = fixture.adapter.claimNextSubmitted(now, Duration.ofMinutes(1))!!
        fixture.adapter.scheduleNextPoll(first.backtestRunId, first.claimToken, now.plusSeconds(5))
        val second = fixture.adapter.claimNextSubmitted(now.plusSeconds(6), Duration.ofMinutes(1))!!
        assertEquals(0.0, fixture.registry.find("refinvest.backtest.retries")
            .tag("operation", "poll").counter()?.count() ?: 0.0)

        fixture.adapter.schedulePollRetry(second.backtestRunId, second.claimToken, now.plusSeconds(16))
        fixture.adapter.claimNextSubmitted(now.plusSeconds(17), Duration.ofMinutes(1))

        assertEquals(1.0, fixture.registry.get("refinvest.backtest.retries")
            .tag("operation", "poll").counter().count())
        assertEquals(3L, fixture.registry.get("refinvest.core.backtest.scheduler.lag")
            .tag("operation", "poll").timer().count())
    }

    private fun fixture(status: BacktestComputeDispatchStatusJpa = BacktestComputeDispatchStatusJpa.PENDING): Fixture {
        val store = Mockito.mock(BacktestComputeDispatchJpaStore::class.java)
        val entity = BacktestComputeDispatchJpaEntity(
            backtestRunId = 42L,
            idempotencyKey = UUID.randomUUID(),
            computeRunId = if (status == BacktestComputeDispatchStatusJpa.SUBMITTED) "compute-run" else null,
            status = status,
            nextAttemptAt = now,
            createdAt = now,
            updatedAt = now,
        )
        Mockito.`when`(store.findClaimable(now, status, PageRequest.of(0, 1))).thenReturn(listOf(entity))
        Mockito.`when`(store.findClaimable(now.plusSeconds(12), status, PageRequest.of(0, 1))).thenReturn(listOf(entity))
        Mockito.`when`(store.findClaimable(now.plusSeconds(6), status, PageRequest.of(0, 1))).thenReturn(listOf(entity))
        Mockito.`when`(store.findClaimable(now.plusSeconds(17), status, PageRequest.of(0, 1))).thenReturn(listOf(entity))
        Mockito.`when`(store.findById(BacktestRunId(42L).value)).thenReturn(Optional.of(entity))
        val registry = SimpleMeterRegistry()
        return Fixture(JpaBacktestComputeDispatchStoreAdapter(store, registry), registry)
    }

    private data class Fixture(val adapter: JpaBacktestComputeDispatchStoreAdapter, val registry: SimpleMeterRegistry)
}
