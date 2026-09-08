package com.refinvest.core.backtest.domain.backtest

import java.math.BigDecimal

data class BuyAndHoldResult(
    val asset: String,
    val equityCurve: List<EquityCurvePoint>,
    val totalReturn: BigDecimal,
    val cagr: BigDecimal?,
    val mdd: BigDecimal,
)
