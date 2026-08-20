package com.refinvest.core.backtest.port.inbound.backtest.list

data class ListBacktestRunsResult(
    val items: List<BacktestRunSummary>,
    val page: Int,
    val size: Int,
    val total: Long,
)
