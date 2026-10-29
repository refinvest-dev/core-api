package com.refinvest.core.strategy.port.inbound.owner

fun interface LookupStrategyOwnerUseCase {
    fun execute(query: LookupStrategyOwnerQuery): LookupStrategyOwnerResult?
}
