package com.refinvest.core.strategy.application.owner

import com.refinvest.core.strategy.port.inbound.owner.LookupStrategyOwnerQuery
import com.refinvest.core.strategy.port.inbound.owner.LookupStrategyOwnerResult
import com.refinvest.core.strategy.port.inbound.owner.LookupStrategyOwnerUseCase
import com.refinvest.core.strategy.port.outbound.persistence.StrategyOwnerReader
import org.springframework.stereotype.Service

@Service
class LookupStrategyOwnerService(
    private val strategyOwnerReader: StrategyOwnerReader,
) : LookupStrategyOwnerUseCase {
    override fun execute(query: LookupStrategyOwnerQuery): LookupStrategyOwnerResult? =
        strategyOwnerReader.findOwnerById(query.strategyId)?.let(::LookupStrategyOwnerResult)
}
