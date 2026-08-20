package com.refinvest.core.strategy.application.strategy.list

import com.refinvest.core.strategy.port.inbound.strategy.list.ListStrategiesQuery
import com.refinvest.core.strategy.port.inbound.strategy.list.ListStrategiesResult
import com.refinvest.core.strategy.port.inbound.strategy.list.ListStrategiesUseCase
import com.refinvest.core.strategy.port.inbound.strategy.list.StrategySummary
import com.refinvest.core.strategy.port.outbound.MemberIdProvider
import com.refinvest.core.strategy.port.outbound.StrategyReader
import org.springframework.stereotype.Service

@Service
class ListStrategiesService(
    private val strategyReader: StrategyReader,
    private val memberIdProvider: MemberIdProvider,
) : ListStrategiesUseCase {
    override fun execute(query: ListStrategiesQuery): ListStrategiesResult = strategyReader
        .findByMemberId(memberIdProvider.currentMemberId(), query.page, query.size)
        .let { strategies ->
            ListStrategiesResult(
                items = strategies.items.map { strategy ->
                    StrategySummary(
                        id = strategy.id,
                        name = strategy.name,
                        createdAt = strategy.createdAt,
                        latestVersionId = strategy.latestVersionId,
                    )
                },
                page = query.page,
                size = query.size,
                total = strategies.total,
            )
        }
}
