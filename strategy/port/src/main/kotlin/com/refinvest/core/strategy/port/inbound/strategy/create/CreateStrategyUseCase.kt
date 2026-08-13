package com.refinvest.core.strategy.port.inbound.strategy.create

fun interface CreateStrategyUseCase {
    fun execute(command: CreateStrategyCommand): CreateStrategyResult
}
