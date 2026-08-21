package com.refinvest.core.subscription.domain.policy

import com.refinvest.core.subscription.domain.valueobject.SubscriptionTier

data class BacktestPolicy(
    val monthlyExecutionLimit: Int,
    val maxConcurrentRuns: Int,
    val maxRequestedPeriodDays: Long,
    val allowedAssets: Set<String>,
) {
    fun allowsAll(assetSymbols: Set<String>): Boolean = assetSymbols.all(allowedAssets::contains)

    companion object {
        private val mvpAssetUniverse = setOf("QQQ", "SPY", "TQQQ", "SOXL", "BTCUSDT", "VIX")
        private val freeAssets = setOf("QQQ", "SPY", "BTCUSDT")

        fun forTier(tier: SubscriptionTier): BacktestPolicy = when (tier) {
            SubscriptionTier.FREE -> BacktestPolicy(
                monthlyExecutionLimit = 30,
                maxConcurrentRuns = 1,
                maxRequestedPeriodDays = 365,
                allowedAssets = freeAssets,
            )
            SubscriptionTier.PRO -> BacktestPolicy(
                monthlyExecutionLimit = 500,
                maxConcurrentRuns = 3,
                maxRequestedPeriodDays = 3_650,
                allowedAssets = mvpAssetUniverse,
            )
        }
    }
}
