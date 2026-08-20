package com.refinvest.core.strategy.port.outbound

data class StrategyPageReadModel(
    val items: List<StrategyReadModel>,
    val total: Long,
)
