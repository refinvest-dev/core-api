package com.refinvest.core.strategy.application.strategy.define

import com.refinvest.core.strategy.domain.strategy.StrategyVersion
import com.refinvest.core.strategy.port.inbound.strategy.define.DefineStrategyVersionCommand
import com.refinvest.core.strategy.port.inbound.strategy.define.DefineStrategyVersionResult
import com.refinvest.core.strategy.port.inbound.strategy.define.DefineStrategyVersionUseCase
import com.refinvest.core.strategy.port.outbound.StrategyStore
import com.refinvest.core.strategy.port.outbound.StrategyVersionIdGenerator
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
open class DefineStrategyVersionService(
    private val strategyStore: StrategyStore,
    private val strategyVersionIdGenerator: StrategyVersionIdGenerator,
    private val clock: Clock,
) : DefineStrategyVersionUseCase {
    @Transactional
    override fun execute(command: DefineStrategyVersionCommand): DefineStrategyVersionResult? {
        val strategy = strategyStore.findById(command.strategyId) ?: return null
        val version = StrategyVersion.create(
            id = strategyVersionIdGenerator.next(),
            strategyId = strategy.id,
            createdAt = clock.instant(),
            primarySignalAsset = command.primarySignalAsset,
            conditions = command.conditions,
            executionAsset = command.executionAsset,
            lag = command.lag,
            exit = command.exit,
        )
        strategy.addVersion(version)
        strategyStore.save(strategy)

        return DefineStrategyVersionResult(
            id = version.id,
            strategyId = version.strategyId,
            createdAt = version.createdAt,
            primarySignalAsset = version.primarySignalAsset,
            conditions = version.conditions,
            executionAsset = version.executionAsset,
            lag = version.lag,
            exit = version.exit,
        )
    }
}
