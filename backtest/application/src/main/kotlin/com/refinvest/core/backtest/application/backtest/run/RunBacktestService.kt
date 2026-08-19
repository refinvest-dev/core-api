package com.refinvest.core.backtest.application.backtest.run

import com.refinvest.core.backtest.domain.BacktestRun
import com.refinvest.core.backtest.domain.valueobject.StrategyId
import com.refinvest.core.backtest.port.inbound.backtest.run.RunBacktestCommand
import com.refinvest.core.backtest.port.inbound.backtest.run.RunBacktestResult
import com.refinvest.core.backtest.port.inbound.backtest.run.RunBacktestUseCase
import com.refinvest.core.backtest.port.outbound.BacktestRunIdGenerator
import com.refinvest.core.backtest.port.outbound.BacktestRunStore
import com.refinvest.core.strategy.domain.valueobject.StrategyVersionId as StrategyVersionIdInStrategy
import com.refinvest.core.strategy.port.inbound.strategy.version.lookup.LookupStrategyVersionOwnerQuery
import com.refinvest.core.strategy.port.inbound.strategy.version.lookup.LookupStrategyVersionOwnerUseCase
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
open class RunBacktestService(
    private val backtestRunStore: BacktestRunStore,
    private val backtestRunIdGenerator: BacktestRunIdGenerator,
    private val lookupStrategyVersionOwnerUseCase: LookupStrategyVersionOwnerUseCase,
    private val clock: Clock,
) : RunBacktestUseCase {
    @Transactional
    override fun execute(command: RunBacktestCommand): RunBacktestResult {
        val owner = lookupStrategyVersionOwnerUseCase.execute(
            LookupStrategyVersionOwnerQuery(StrategyVersionIdInStrategy(command.strategyVersionId.value)),
        ) ?: throw NoSuchElementException("Strategy version not found")
        val backtestRun = BacktestRun.createPending(
            id = backtestRunIdGenerator.next(),
            strategyId = StrategyId(owner.strategyId.value),
            strategyVersionId = command.strategyVersionId,
            requestedPeriod = command.period,
            feeModel = command.feeModel,
            createdAt = clock.instant(),
        )
        backtestRunStore.save(backtestRun)
        return RunBacktestResult(
            id = backtestRun.id,
            strategyId = backtestRun.strategyId,
            strategyVersionId = backtestRun.strategyVersionId,
            requestedPeriod = backtestRun.requestedPeriod,
            feeModel = backtestRun.feeModel,
            status = backtestRun.status,
            createdAt = backtestRun.createdAt,
        )
    }
}
