package com.refinvest.core.backtest.port.outbound.persistence

data class BacktestRunPageReadModel(
    val items: List<BacktestRunReadModel>,
    val total: Long,
)
