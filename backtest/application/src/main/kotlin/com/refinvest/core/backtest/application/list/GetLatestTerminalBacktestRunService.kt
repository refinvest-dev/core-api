package com.refinvest.core.backtest.application.list

import com.refinvest.core.backtest.domain.valueobject.BacktestRunStatus
import com.refinvest.core.backtest.domain.valueobject.StrategyVersionId
import com.refinvest.core.backtest.port.inbound.list.BacktestRunSummary
import com.refinvest.core.backtest.port.inbound.list.GetLatestTerminalBacktestRunUseCase
import com.refinvest.core.backtest.port.outbound.member.BacktestMemberIdProvider
import com.refinvest.core.backtest.port.outbound.persistence.BacktestRunReader
import com.refinvest.core.strategy.port.inbound.version.backtest.LookupStrategyVersionForBacktestQuery
import com.refinvest.core.strategy.port.inbound.version.backtest.LookupStrategyVersionForBacktestUseCase
import org.springframework.stereotype.Service

@Service
class GetLatestTerminalBacktestRunService(
    private val backtestRunReader: BacktestRunReader,
    private val lookupStrategyVersionForBacktestUseCase: LookupStrategyVersionForBacktestUseCase,
    private val backtestMemberIdProvider: BacktestMemberIdProvider,
) : GetLatestTerminalBacktestRunUseCase {
    override fun execute(strategyVersionId: StrategyVersionId): BacktestRunSummary? {
        val strategyVersion = lookupStrategyVersionForBacktestUseCase.execute(
            LookupStrategyVersionForBacktestQuery(strategyVersionId.value),
        ) ?: throw NoSuchElementException("Strategy version not found")
        if (strategyVersion.ownerMemberId != backtestMemberIdProvider.currentMemberId()) {
            throw NoSuchElementException("Strategy version not found")
        }

        return backtestRunReader.findLatestTerminalByStrategyVersionId(strategyVersionId)
            ?.takeIf { it.status in TERMINAL_STATUSES }
            ?.let { run ->
                BacktestRunSummary(
                    id = run.id,
                    strategyId = run.strategyId,
                    strategyVersionId = run.strategyVersionId,
                    requestedPeriod = run.requestedPeriod,
                    feeModel = run.feeModel,
                    status = run.status,
                    createdAt = run.createdAt,
                )
            }
    }

    private companion object {
        val TERMINAL_STATUSES = setOf(BacktestRunStatus.COMPLETED, BacktestRunStatus.FAILED)
    }
}
