package com.refinvest.core.subscription.port.inbound.usage

import com.refinvest.core.subscription.domain.valueobject.SubscriptionTier

data class GetUsageResult(
    val tier: SubscriptionTier,
    val backtestsUsedThisMonth: Long,
    val backtestMonthlyLimit: Int,
    val allowedAssets: List<String>,
    val maxBacktestPeriodDays: Long,
)
