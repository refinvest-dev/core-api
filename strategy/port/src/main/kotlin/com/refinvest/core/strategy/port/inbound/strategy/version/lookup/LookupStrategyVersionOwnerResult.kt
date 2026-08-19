package com.refinvest.core.strategy.port.inbound.strategy.version.lookup

import com.refinvest.core.strategy.domain.valueobject.StrategyId
import com.refinvest.core.strategy.domain.valueobject.StrategyVersionId

data class LookupStrategyVersionOwnerResult(
    val strategyVersionId: StrategyVersionId,
    val strategyId: StrategyId,
)
