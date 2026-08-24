package com.refinvest.core.backtest.application.backtest.get

import com.refinvest.core.backtest.domain.valueobject.BacktestRunStatus
import com.refinvest.core.backtest.port.inbound.backtest.get.GetBacktestResultQuery
import com.refinvest.core.backtest.port.inbound.backtest.get.GetBacktestResultResult
import com.refinvest.core.backtest.port.inbound.backtest.get.GetBacktestResultUseCase
import com.refinvest.core.backtest.port.outbound.BacktestMemberIdProvider
import com.refinvest.core.backtest.port.outbound.BacktestResultReader
import com.refinvest.core.backtest.port.outbound.BacktestRunReader
import com.refinvest.core.backtest.port.outbound.BacktestRunReadModel
import com.refinvest.core.strategy.port.inbound.version.backtest.LookupStrategyVersionForBacktestQuery
import com.refinvest.core.strategy.port.inbound.version.backtest.LookupStrategyVersionForBacktestUseCase
import org.springframework.stereotype.Service

@Service
class GetBacktestResultService(
    private val backtestRunReader: BacktestRunReader,
    private val backtestResultReader: BacktestResultReader,
    private val lookupStrategyVersionForBacktestUseCase: LookupStrategyVersionForBacktestUseCase,
    private val backtestMemberIdProvider: BacktestMemberIdProvider,
) : GetBacktestResultUseCase {
    override fun execute(query: GetBacktestResultQuery): GetBacktestResultResult? {
        val backtestRun = backtestRunReader.findById(query.backtestRunId) ?: return null
        val strategyVersion = lookupStrategyVersionForBacktestUseCase.execute(
            LookupStrategyVersionForBacktestQuery(backtestRun.strategyVersionId.value),
        ) ?: return null
        if (strategyVersion.ownerMemberId != backtestMemberIdProvider.currentMemberId()) return null

        return GetBacktestResultResult(
            id = backtestRun.id,
            strategyId = backtestRun.strategyId,
            strategyVersionId = backtestRun.strategyVersionId,
            requestedPeriod = backtestRun.requestedPeriod,
            feeModel = backtestRun.feeModel,
            status = backtestRun.status,
            actualPeriod = backtestRun.actualPeriod,
            datasetSnapshotId = backtestRun.datasetSnapshotId,
            engineVersion = backtestRun.engineVersion,
            result = completedResultOf(backtestRun),
            failureReason = backtestRun.failureReason,
            createdAt = backtestRun.createdAt,
        )
    }

    private fun completedResultOf(backtestRun: BacktestRunReadModel) =
        if (backtestRun.status != BacktestRunStatus.COMPLETED) {
            null
        } else {
            requireNotNull(backtestResultReader.findByBacktestRunId(backtestRun.id)) {
                "COMPLETED BacktestRun requires a BacktestResult"
            }.also { backtestResult ->
                require(backtestResult.backtestRunId == backtestRun.id) {
                    "BacktestResult belongs to another BacktestRun"
                }
            }
        }
}
