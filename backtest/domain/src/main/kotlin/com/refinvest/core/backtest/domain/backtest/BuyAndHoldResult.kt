package com.refinvest.core.backtest.domain.backtest

import java.math.BigDecimal

data class BuyAndHoldResult(
    val totalReturn: BigDecimal,
    val cagr: BigDecimal,
    val mdd: BigDecimal,
)
