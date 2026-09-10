package com.refinvest.core.backtest.application.list

import com.refinvest.core.backtest.domain.valueobject.BacktestRunStatus
import com.refinvest.core.backtest.domain.valueobject.StrategyVersionId
import com.refinvest.core.backtest.port.inbound.list.BacktestRunSummary
import com.refinvest.core.backtest.port.inbound.list.GetLatestCompletedBacktestRunUseCase
import com.refinvest.core.backtest.port.outbound.member.BacktestMemberIdProvider
import com.refinvest.core.backtest.port.outbound.persistence.BacktestRunReader
import com.refinvest.core.strategy.port.inbound.version.backtest.LookupStrategyVersionForBacktestQuery
import com.refinvest.core.strategy.port.inbound.version.backtest.LookupStrategyVersionForBacktestUseCase
import org.springframework.stereotype.Service

@Service
class GetLatestCompletedBacktestRunService(
    private val backtestRunReader: BacktestRunReader,
    private val lookupStrategyVersionForBacktestUseCase: LookupStrategyVersionForBacktestUseCase,
    private val backtestMemberIdProvider: BacktestMemberIdProvider,
) : GetLatestCompletedBacktestRunUseCase {
    override fun execute(strategyVersionId: StrategyVersionId): BacktestRunSummary? {
        val strategyVersion = lookupStrategyVersionForBacktestUseCase.execute(
            LookupStrategyVersionForBacktestQuery(strategyVersionId.value),
        ) ?: throw NoSuchElementException("Strategy version not found")
        if (strategyVersion.ownerMemberId != backtestMemberIdProvider.currentMemberId()) {
            throw NoSuchElementException("Strategy version not found")
        }

        return backtestRunReader.findLatestCompletedByStrategyVersionId(strategyVersionId)
            ?.takeIf { it.status == BacktestRunStatus.COMPLETED }
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
}
