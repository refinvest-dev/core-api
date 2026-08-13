package com.refinvest.core.strategy.port.inbound.strategy.get

fun interface GetStrategyUseCase {
    fun execute(query: GetStrategyQuery): GetStrategyResult?
}
