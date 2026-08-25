package com.refinvest.core.subscription.application.subscription.usage

import com.refinvest.core.backtest.port.inbound.usage.GetMonthlyBacktestUsageQuery
import com.refinvest.core.backtest.port.inbound.usage.GetMonthlyBacktestUsageUseCase
import com.refinvest.core.subscription.domain.policy.BacktestPolicy
import com.refinvest.core.subscription.domain.valueobject.SubscriptionTier
import com.refinvest.core.subscription.port.inbound.subscription.usage.GetUsageQuery
import com.refinvest.core.subscription.port.inbound.subscription.usage.GetUsageResult
import com.refinvest.core.subscription.port.inbound.subscription.usage.GetUsageUseCase
import com.refinvest.core.subscription.port.outbound.SubscriptionReader
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.YearMonth
import java.time.ZoneOffset

@Service
open class GetUsageService(
    private val subscriptionReader: SubscriptionReader,
    private val getMonthlyBacktestUsageUseCase: GetMonthlyBacktestUsageUseCase,
    private val clock: Clock,
) : GetUsageUseCase {
    @Transactional(readOnly = true)
    override fun execute(query: GetUsageQuery): GetUsageResult {
        val month = YearMonth.now(clock.withZone(ZoneOffset.UTC))
        val startInclusive = month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant()
        val endExclusive = month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant()
        val tier = subscriptionReader.findByMemberId(query.memberId)?.tier ?: SubscriptionTier.FREE
        val policy = BacktestPolicy.forTier(tier)
        val backtestsUsed = getMonthlyBacktestUsageUseCase.execute(
            GetMonthlyBacktestUsageQuery(query.memberId, startInclusive, endExclusive),
        ).backtestsUsed

        return GetUsageResult(
            tier = tier,
            backtestsUsedThisMonth = backtestsUsed,
            backtestMonthlyLimit = policy.monthlyExecutionLimit,
            allowedAssets = policy.allowedAssets.sorted(),
            maxBacktestPeriodDays = policy.maxRequestedPeriodDays,
        )
    }
}
