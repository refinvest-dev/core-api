package com.refinvest.core.strategy.port.inbound.strategy.version.lookup

fun interface LookupStrategyVersionOwnerUseCase {
    fun execute(query: LookupStrategyVersionOwnerQuery): LookupStrategyVersionOwnerResult?
}
