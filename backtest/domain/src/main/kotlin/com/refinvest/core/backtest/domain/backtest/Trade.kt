package com.refinvest.core.backtest.domain.backtest

import java.math.BigDecimal
import java.time.Instant

data class Trade(
    val signalTime: Instant,
    val entryTime: Instant,
    val entryPrice: BigDecimal,
    val exitTime: Instant,
    val exitPrice: BigDecimal,
    val returnPct: BigDecimal,
    val holdingPeriod: Int,
)
