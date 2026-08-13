package com.refinvest.core.strategy.application.strategy.get

import com.refinvest.core.strategy.port.inbound.strategy.get.GetStrategyQuery
import com.refinvest.core.strategy.port.inbound.strategy.get.GetStrategyResult
import com.refinvest.core.strategy.port.inbound.strategy.get.GetStrategyUseCase
import com.refinvest.core.strategy.port.outbound.StrategyReader
import org.springframework.stereotype.Service

@Service
class GetStrategyService(
    private val strategyReader: StrategyReader,
) : GetStrategyUseCase {
    override fun execute(query: GetStrategyQuery): GetStrategyResult? =
        strategyReader.findById(query.strategyId)?.let { strategy ->
            GetStrategyResult(
                id = strategy.id,
                name = strategy.name,
                createdAt = strategy.createdAt,
                latestVersionId = strategy.latestVersionId,
            )
        }
}
