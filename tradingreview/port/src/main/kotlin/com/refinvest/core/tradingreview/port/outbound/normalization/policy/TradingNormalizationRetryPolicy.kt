package com.refinvest.core.tradingreview.port.outbound.normalization.policy

import com.refinvest.core.tradingreview.domain.*
import java.time.Duration

data class TradingNormalizationRetryPolicy(
    val version: String,
    val maxAttempts: Int,
    val maxElapsedTime: Duration,
    val initialDelay: Duration,
    val maxDelay: Duration,
    val jitter: String,
)
