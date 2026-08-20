package com.refinvest.core.strategy.port.inbound.strategy.list

fun interface ListStrategiesUseCase {
    fun execute(query: ListStrategiesQuery): ListStrategiesResult
}
