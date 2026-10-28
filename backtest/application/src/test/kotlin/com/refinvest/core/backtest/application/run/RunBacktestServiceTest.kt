package com.refinvest.core.backtest.application.run

import com.refinvest.core.backtest.domain.BacktestRun
import com.refinvest.core.backtest.domain.valueobject.BacktestRunId
import com.refinvest.core.backtest.domain.valueobject.BacktestRunStatus
import com.refinvest.core.backtest.domain.valueobject.FeeModel
import com.refinvest.core.backtest.domain.valueobject.Percent
import com.refinvest.core.backtest.domain.valueobject.Period
import com.refinvest.core.backtest.domain.valueobject.StrategyVersionId
import com.refinvest.core.backtest.domain.valueobject.StrategyId
import com.refinvest.core.backtest.port.inbound.run.RunBacktestCommand
import com.refinvest.core.backtest.port.outbound.id.BacktestRunIdGenerator
import com.refinvest.core.backtest.port.outbound.member.BacktestMemberIdProvider
import com.refinvest.core.backtest.port.outbound.compute.ComputeIdempotencyKeyGenerator
import com.refinvest.core.backtest.port.outbound.compute.ComputeIdempotencyKey
import com.refinvest.core.backtest.port.outbound.persistence.dispatch.BacktestComputeDispatchStore
import com.refinvest.core.backtest.port.outbound.persistence.dispatch.ClaimedBacktestComputeDispatch
import com.refinvest.core.backtest.port.outbound.persistence.dispatch.PendingBacktestComputeDispatch
import com.refinvest.core.backtest.port.outbound.persistence.BacktestQuotaReservation
import com.refinvest.core.backtest.port.outbound.persistence.BacktestQuotaRequest
import com.refinvest.core.backtest.port.outbound.persistence.BacktestQuotaStore
import com.refinvest.core.backtest.port.outbound.persistence.BacktestRunStore
import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.strategy.port.inbound.version.backtest.LookupStrategyVersionForBacktestResult
import com.refinvest.core.strategy.port.inbound.version.backtest.LookupStrategyVersionForBacktestUseCase
import com.refinvest.core.subscription.domain.Subscription
import com.refinvest.core.subscription.domain.valueobject.SubscriptionTier
import com.refinvest.core.subscription.port.inbound.get.GetSubscriptionUseCase
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZoneId
import java.time.YearMonth
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RunBacktestServiceTest {
    @Test
    fun `creates pending run and reserves the same UTC month across midnight`() {
        var saved: BacktestRun? = null
        var enqueued: PendingBacktestComputeDispatch? = null
        var reservation: BacktestQuotaRequest? = null
        var clockReads = 0
        val id = BacktestRunId(10L)
        val service = RunBacktestService(
            backtestRunStore = object : BacktestRunStore {
                override fun save(backtestRun: BacktestRun) {
                    saved = backtestRun
                }

                override fun findById(id: BacktestRunId): BacktestRun? = null
            },
            backtestRunIdGenerator = BacktestRunIdGenerator { id },
            lookupStrategyVersionForBacktestUseCase = LookupStrategyVersionForBacktestUseCase {
                LookupStrategyVersionForBacktestResult(30L, MemberId(1L), setOf("QQQ"))
            },
            getSubscriptionUseCase = GetSubscriptionUseCase { Subscription.restore(it.memberId, SubscriptionTier.FREE).let { subscription ->
                com.refinvest.core.subscription.port.inbound.get.GetSubscriptionResult(subscription.id, subscription.tier)
            } },
            backtestMemberIdProvider = BacktestMemberIdProvider { MemberId(1L) },
            backtestQuotaStore = object : BacktestQuotaStore {
                override fun reserve(request: BacktestQuotaRequest): BacktestQuotaReservation {
                    reservation = request
                    return BacktestQuotaReservation.RESERVED
                }

                override fun releaseConcurrentCapacity(backtestRunId: BacktestRunId) = Unit
            },
            backtestComputeDispatchStore = object : BacktestComputeDispatchStore {
                override fun enqueue(dispatch: PendingBacktestComputeDispatch) { enqueued = dispatch }
                override fun claimNext(now: Instant, leaseDuration: Duration): ClaimedBacktestComputeDispatch? = null
                override fun claimNextSubmitted(
                    now: Instant,
                    leaseDuration: Duration,
                ): com.refinvest.core.backtest.port.outbound.persistence.dispatch.ClaimedSubmittedBacktestComputeDispatch? = null
                override fun markAccepted(backtestRunId: BacktestRunId, claimToken: UUID, computeRunId: String) = Unit
                override fun scheduleRetry(backtestRunId: BacktestRunId, claimToken: UUID, nextAttemptAt: Instant) = Unit
                override fun markRejected(backtestRunId: BacktestRunId, claimToken: UUID): Boolean = false
                override fun scheduleNextPoll(
                    backtestRunId: BacktestRunId,
                    claimToken: UUID,
                    nextAttemptAt: Instant,
                ) = Unit
                override fun markTerminal(backtestRunId: BacktestRunId, claimToken: UUID) = Unit
            },
            computeIdempotencyKeyGenerator = ComputeIdempotencyKeyGenerator {
                ComputeIdempotencyKey(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"))
            },
            clock = object : Clock() {
                override fun getZone(): ZoneId = ZoneOffset.UTC
                override fun withZone(zone: ZoneId): Clock = this
                override fun instant(): Instant = if (clockReads++ == 0)
                    Instant.parse("2025-12-31T23:59:59Z") else Instant.parse("2026-01-01T00:00:00Z")
            },
        )

        val result = service.execute(
            RunBacktestCommand(
                strategyVersionId = StrategyVersionId(20L),
                period = Period(LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31")),
                feeModel = FeeModel(Percent(BigDecimal.ZERO), Percent(BigDecimal.ZERO)),
            ),
        )

        assertEquals(id, result.id)
        assertEquals(BacktestRunStatus.PENDING, saved?.status)
        assertEquals(StrategyId(30L), saved?.strategyId)
        assertEquals(StrategyVersionId(20L), saved?.strategyVersionId)
        assertNull(saved?.datasetSnapshotId)
        assertNull(saved?.engineVersion)
        assertEquals(id, enqueued?.backtestRunId)
        assertEquals(id, reservation?.backtestRunId)
        assertEquals(YearMonth.of(2025, 12), reservation?.quotaMonth)
        assertEquals(Instant.parse("2025-12-31T23:59:59Z"), saved?.createdAt)
    }
}
