package com.refinvest.core.backtest.domain.backtest

import java.math.BigDecimal

data class BacktestResultMetrics(
    val totalReturn: BigDecimal,
    val cagr: BigDecimal,
    val mdd: BigDecimal,
    val sharpe: BigDecimal,
    val winRate: BigDecimal,
    val tradeCount: Int,
    val avgTradeReturn: BigDecimal,
    val avgHoldingPeriod: BigDecimal,
    val profitFactor: BigDecimal,
)
