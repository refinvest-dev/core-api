package com.refinvest.core.strategy.port.outbound

import com.refinvest.core.strategy.domain.valueobject.StrategyVersionId

fun interface StrategyVersionOwnerReader {
    fun findById(id: StrategyVersionId): StrategyVersionOwnerReadModel?
}
