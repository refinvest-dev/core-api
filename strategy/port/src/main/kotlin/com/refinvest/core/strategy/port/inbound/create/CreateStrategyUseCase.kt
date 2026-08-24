package com.refinvest.core.strategy.port.inbound.create

fun interface CreateStrategyUseCase {
    fun execute(command: CreateStrategyCommand): CreateStrategyResult
}
