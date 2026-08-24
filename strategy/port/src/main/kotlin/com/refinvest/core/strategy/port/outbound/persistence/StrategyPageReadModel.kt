package com.refinvest.core.strategy.port.outbound.persistence

data class StrategyPageReadModel(
    val items: List<StrategyReadModel>,
    val total: Long,
)
