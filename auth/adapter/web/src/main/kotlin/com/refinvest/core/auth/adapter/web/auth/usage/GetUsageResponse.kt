package com.refinvest.core.auth.adapter.web.auth.usage

import com.refinvest.core.subscription.port.inbound.subscription.usage.GetUsageResult

data class GetUsageResponse(
    val tier: String,
    val backtestsUsedThisMonth: Long,
    val backtestMonthlyLimit: Int?,
    val allowedAssets: List<String>?,
    val maxBacktestPeriodDays: Int?,
    val strategySaveEnabled: Boolean,
) {
    companion object {
        fun from(result: GetUsageResult): GetUsageResponse = GetUsageResponse(
            tier = result.tier.name,
            backtestsUsedThisMonth = result.backtestsUsedThisMonth,
            backtestMonthlyLimit = result.backtestMonthlyLimit,
            allowedAssets = result.allowedAssets,
            maxBacktestPeriodDays = result.maxBacktestPeriodDays,
            strategySaveEnabled = result.strategySaveEnabled,
        )
    }
}
