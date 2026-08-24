package com.refinvest.core.strategy.application.get

import com.refinvest.core.strategy.port.inbound.get.GetStrategyQuery
import com.refinvest.core.strategy.port.inbound.get.GetStrategyResult
import com.refinvest.core.strategy.port.inbound.get.GetStrategyVersionResult
import com.refinvest.core.strategy.port.inbound.get.GetStrategyUseCase
import com.refinvest.core.strategy.port.outbound.member.MemberIdProvider
import com.refinvest.core.strategy.port.outbound.persistence.StrategyReader
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
