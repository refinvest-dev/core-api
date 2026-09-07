package com.refinvest.core.subscription.application.usage

import com.refinvest.core.backtest.port.inbound.usage.GetMonthlyBacktestUsageQuery
import com.refinvest.core.backtest.port.inbound.usage.GetMonthlyBacktestUsageResult
import com.refinvest.core.backtest.port.inbound.usage.GetMonthlyBacktestUsageUseCase
import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.subscription.domain.Subscription
import com.refinvest.core.subscription.domain.valueobject.SubscriptionTier
import com.refinvest.core.subscription.port.inbound.usage.GetUsageQuery
import com.refinvest.core.subscription.port.outbound.persistence.SubscriptionReader
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class GetUsageServiceTest {
    @Test
    fun `returns current UTC month usage for subscription tier`() {
        val memberId = MemberId(1)
        val backtestUsage = RecordingBacktestUsage(7)
        val service = GetUsageService(
            subscriptionReader = SubscriptionReader { Subscription.restore(memberId, SubscriptionTier.PRO) },
            getMonthlyBacktestUsageUseCase = backtestUsage,
            clock = Clock.fixed(Instant.parse("2026-08-21T12:00:00Z"), ZoneOffset.UTC),
        )

        val result = service.execute(GetUsageQuery(memberId))

        assertEquals(SubscriptionTier.PRO, result.tier)
        assertEquals(7, result.backtestsUsedThisMonth)
        assertEquals(Instant.parse("2026-08-01T00:00:00Z"), backtestUsage.query?.startInclusive)
        assertEquals(Instant.parse("2026-09-01T00:00:00Z"), backtestUsage.query?.endExclusive)
        assertEquals(500, result.backtestMonthlyLimit)
        assertEquals(listOf("BTCUSDT", "QQQ", "SOXL", "SPY", "TQQQ"), result.allowedAssets)
        assertEquals(3_650, result.maxBacktestPeriodDays)
    }

    @Test
    fun `treats a missing persisted subscription as free`() {
        val service = GetUsageService(
            subscriptionReader = SubscriptionReader { null },
            getMonthlyBacktestUsageUseCase = RecordingBacktestUsage(0),
            clock = Clock.systemUTC(),
        )

        val result = service.execute(GetUsageQuery(MemberId(1)))

        assertEquals(SubscriptionTier.FREE, result.tier)
        assertEquals(30, result.backtestMonthlyLimit)
    }

    private class RecordingBacktestUsage(
        private val count: Long,
    ) : GetMonthlyBacktestUsageUseCase {
        var query: GetMonthlyBacktestUsageQuery? = null

        override fun execute(query: GetMonthlyBacktestUsageQuery): GetMonthlyBacktestUsageResult {
            this.query = query
            return GetMonthlyBacktestUsageResult(count)
        }
    }
}
