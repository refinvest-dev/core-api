package com.refinvest.core.backtest.adapter.web.backtest.get

import com.refinvest.core.backtest.domain.backtest.BacktestResultMetrics
import java.math.BigDecimal

data class BacktestResultMetricsResponse(
    val totalReturn: BigDecimal?,
    val cagr: BigDecimal?,
    val mdd: BigDecimal?,
    val sharpe: BigDecimal?,
    val winRate: BigDecimal?,
    val tradeCount: Int,
    val avgTradeReturn: BigDecimal?,
    val avgHoldingPeriod: BigDecimal?,
    val profitFactor: BigDecimal?,
) {
    companion object {
        fun from(metrics: BacktestResultMetrics): BacktestResultMetricsResponse = BacktestResultMetricsResponse(
            totalReturn = metrics.totalReturn,
            cagr = metrics.cagr,
            mdd = metrics.mdd,
            sharpe = metrics.sharpe,
            winRate = metrics.winRate,
            tradeCount = metrics.tradeCount,
            avgTradeReturn = metrics.avgTradeReturn,
            avgHoldingPeriod = metrics.avgHoldingPeriod,
            profitFactor = metrics.profitFactor,
        )
    }
}
