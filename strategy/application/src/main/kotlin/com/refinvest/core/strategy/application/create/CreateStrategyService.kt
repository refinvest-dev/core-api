package com.refinvest.core.strategy.application.create

import com.refinvest.core.strategy.domain.Strategy
import com.refinvest.core.strategy.port.inbound.create.CreateStrategyCommand
import com.refinvest.core.strategy.port.inbound.create.CreateStrategyResult
import com.refinvest.core.strategy.port.inbound.create.CreateStrategyUseCase
import com.refinvest.core.strategy.port.outbound.member.MemberIdProvider
import com.refinvest.core.strategy.port.outbound.id.StrategyIdGenerator
import com.refinvest.core.strategy.port.outbound.persistence.StrategyStore
import org.springframework.stereotype.Service
import java.time.Clock

@Service
class CreateStrategyService(
    private val strategyStore: StrategyStore,
    private val strategyIdGenerator: StrategyIdGenerator,
    private val memberIdProvider: MemberIdProvider,
    private val clock: Clock,
) : CreateStrategyUseCase {
    override fun execute(command: CreateStrategyCommand): CreateStrategyResult {
        val strategy = Strategy.create(
            id = strategyIdGenerator.next(),
            memberId = memberIdProvider.currentMemberId(),
            name = command.name,
            createdAt = clock.instant(),
        )
        strategyStore.save(strategy)
        return CreateStrategyResult(strategy.id, strategy.createdAt)
    }
}
