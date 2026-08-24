package com.refinvest.core.strategy.application.list

import com.refinvest.core.strategy.port.inbound.list.ListStrategiesQuery
import com.refinvest.core.strategy.port.inbound.list.ListStrategiesResult
import com.refinvest.core.strategy.port.inbound.list.ListStrategiesUseCase
import com.refinvest.core.strategy.port.inbound.list.StrategySummary
import com.refinvest.core.strategy.port.outbound.member.MemberIdProvider
import com.refinvest.core.strategy.port.outbound.persistence.StrategyReader
import org.springframework.stereotype.Service

@Service
class ListStrategiesService(
    private val strategyReader: StrategyReader,
    private val memberIdProvider: MemberIdProvider,
) : ListStrategiesUseCase {
    override fun execute(query: ListStrategiesQuery): ListStrategiesResult {
        val strategyPage = strategyReader.findByMemberId(
            memberId = memberIdProvider.currentMemberId(),
            page = query.page,
            size = query.size,
        )

        return ListStrategiesResult(
            items = strategyPage.items.map { strategy ->
                StrategySummary(
                    id = strategy.id,
                    name = strategy.name,
                    createdAt = strategy.createdAt,
                    latestVersionId = strategy.latestVersionId,
                )
            },
            page = query.page,
            size = query.size,
            total = strategyPage.total,
        )
    }
}
