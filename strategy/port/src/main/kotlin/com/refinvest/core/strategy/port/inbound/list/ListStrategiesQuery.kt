package com.refinvest.core.strategy.port.inbound.list

data class ListStrategiesQuery(
    val page: Int = 0,
    val size: Int = 20,
) {
    init {
        require(page >= 0) { "Page must not be negative" }
        require(size in 1..100) { "Size must be between 1 and 100" }
    }
}
