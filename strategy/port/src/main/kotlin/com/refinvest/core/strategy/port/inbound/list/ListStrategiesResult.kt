package com.refinvest.core.strategy.port.inbound.list

data class ListStrategiesResult(
    val items: List<StrategySummary>,
    val page: Int,
    val size: Int,
    val total: Long,
)
