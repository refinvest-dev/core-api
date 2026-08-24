package com.refinvest.core.strategy.port.inbound.version.lookup

fun interface LookupStrategyVersionOwnerUseCase {
    fun execute(query: LookupStrategyVersionOwnerQuery): LookupStrategyVersionOwnerResult?
}
