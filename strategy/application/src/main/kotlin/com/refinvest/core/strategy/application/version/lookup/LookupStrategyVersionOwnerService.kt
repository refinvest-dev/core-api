package com.refinvest.core.strategy.application.version.lookup

import com.refinvest.core.strategy.port.inbound.version.lookup.LookupStrategyVersionOwnerQuery
import com.refinvest.core.strategy.port.inbound.version.lookup.LookupStrategyVersionOwnerResult
import com.refinvest.core.strategy.port.inbound.version.lookup.LookupStrategyVersionOwnerUseCase
import com.refinvest.core.strategy.port.outbound.persistence.version.StrategyVersionOwnerReader
import org.springframework.stereotype.Service

@Service
class LookupStrategyVersionOwnerService(
    private val strategyVersionOwnerReader: StrategyVersionOwnerReader,
) : LookupStrategyVersionOwnerUseCase {
    override fun execute(query: LookupStrategyVersionOwnerQuery): LookupStrategyVersionOwnerResult? =
        strategyVersionOwnerReader.findById(query.strategyVersionId)?.let { version ->
            LookupStrategyVersionOwnerResult(
                strategyVersionId = version.strategyVersionId,
                strategyId = version.strategyId,
            )
        }
}
