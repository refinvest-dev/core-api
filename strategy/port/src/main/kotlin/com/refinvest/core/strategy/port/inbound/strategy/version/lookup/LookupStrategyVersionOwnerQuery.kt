package com.refinvest.core.strategy.port.inbound.strategy.version.lookup

import com.refinvest.core.strategy.domain.valueobject.StrategyVersionId

data class LookupStrategyVersionOwnerQuery(
    val strategyVersionId: StrategyVersionId,
)
