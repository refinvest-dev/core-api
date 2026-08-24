package com.refinvest.core.strategy.application.strategy.get

import com.refinvest.core.strategy.port.inbound.strategy.get.GetStrategyQuery
import com.refinvest.core.strategy.port.inbound.strategy.get.GetStrategyResult
import com.refinvest.core.strategy.port.inbound.strategy.get.GetStrategyVersionResult
import com.refinvest.core.strategy.port.inbound.strategy.get.GetStrategyUseCase
import com.refinvest.core.strategy.port.outbound.MemberIdProvider
import com.refinvest.core.strategy.port.outbound.StrategyReader
import org.springframework.stereotype.Service

@Service
class GetStrategyService(
    private val strategyReader: StrategyReader,
    private val memberIdProvider: MemberIdProvider,
) : GetStrategyUseCase {
    override fun execute(query: GetStrategyQuery): GetStrategyResult? {
        val strategy = strategyReader.findById(query.strategyId) ?: return null
        if (strategy.memberId != memberIdProvider.currentMemberId()) return null

        return GetStrategyResult(
            id = strategy.id,
            name = strategy.name,
            createdAt = strategy.createdAt,
            latestVersionId = strategy.latestVersionId,
            versions = strategy.versions.map { version ->
                GetStrategyVersionResult(
                    id = version.id,
                    createdAt = version.createdAt,
                    primarySignalAsset = version.primarySignalAsset,
                    conditions = version.conditions,
                    executionAsset = version.executionAsset,
                    lag = version.lag,
                    exit = version.exit,
                )
            },
        )
    }
}
