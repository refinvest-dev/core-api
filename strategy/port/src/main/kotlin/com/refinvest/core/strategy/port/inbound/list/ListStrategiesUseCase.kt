package com.refinvest.core.strategy.port.inbound.list

fun interface ListStrategiesUseCase {
    fun execute(query: ListStrategiesQuery): ListStrategiesResult
}
