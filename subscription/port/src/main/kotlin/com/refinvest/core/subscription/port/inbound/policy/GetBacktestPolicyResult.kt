package com.refinvest.core.subscription.port.inbound.policy

data class GetBacktestPolicyResult(
    val monthlyExecutionLimit: Int,
    val maxConcurrentRuns: Int,
    val maxRequestedPeriodDays: Long,
    val allowedAssets: Set<String>,
)
