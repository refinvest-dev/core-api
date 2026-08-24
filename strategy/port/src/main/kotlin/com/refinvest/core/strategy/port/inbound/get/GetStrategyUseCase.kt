package com.refinvest.core.strategy.port.inbound.get

fun interface GetStrategyUseCase {
    fun execute(query: GetStrategyQuery): GetStrategyResult?
}
