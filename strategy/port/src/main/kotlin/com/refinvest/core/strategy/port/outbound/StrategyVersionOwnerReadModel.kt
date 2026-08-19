package com.refinvest.core.strategy.port.outbound

import com.refinvest.core.strategy.domain.valueobject.StrategyId
import com.refinvest.core.strategy.domain.valueobject.StrategyVersionId

data class StrategyVersionOwnerReadModel(
    val strategyVersionId: StrategyVersionId,
    val strategyId: StrategyId,
)
