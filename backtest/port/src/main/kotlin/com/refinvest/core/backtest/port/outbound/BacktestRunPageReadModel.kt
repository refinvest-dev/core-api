package com.refinvest.core.backtest.port.outbound

data class BacktestRunPageReadModel(
    val items: List<BacktestRunReadModel>,
    val total: Long,
)
