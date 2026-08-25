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
import com.refinvest.core.backtest.port.outbound.persistence.BacktestQuotaReservation
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RunBacktestServiceTest {
    @Test
    fun `creates and persists only a pending backtest run`() {
        var saved: BacktestRun? = null
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
                override fun reserve(
                    memberId: MemberId,
                    quotaMonth: java.time.YearMonth,
                    monthlyExecutionLimit: Int,
                    maxConcurrentRuns: Int,
                ) = BacktestQuotaReservation.RESERVED

                override fun releaseConcurrentCapacity(memberId: MemberId, quotaMonth: java.time.YearMonth) = Unit
            },
            clock = Clock.fixed(Instant.parse("2026-08-11T00:00:00Z"), ZoneOffset.UTC),
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
    }
}
