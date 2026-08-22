package com.refinvest.core.backtest.application.backtest.get

import com.refinvest.core.backtest.domain.valueobject.BacktestRunStatus
import com.refinvest.core.backtest.port.inbound.backtest.get.GetBacktestResultQuery
import com.refinvest.core.backtest.port.inbound.backtest.get.GetBacktestResultResult
import com.refinvest.core.backtest.port.inbound.backtest.get.GetBacktestResultUseCase
import com.refinvest.core.backtest.port.outbound.BacktestMemberIdProvider
import com.refinvest.core.backtest.port.outbound.BacktestResultReader
import com.refinvest.core.backtest.port.outbound.BacktestRunReader
import com.refinvest.core.strategy.port.inbound.strategy.version.backtest.LookupStrategyVersionForBacktestQuery
import com.refinvest.core.strategy.port.inbound.strategy.version.backtest.LookupStrategyVersionForBacktestUseCase
import org.springframework.stereotype.Service

@Service
class GetBacktestResultService(
    private val backtestRunReader: BacktestRunReader,
    private val backtestResultReader: BacktestResultReader,
    private val lookupStrategyVersionForBacktestUseCase: LookupStrategyVersionForBacktestUseCase,
    private val backtestMemberIdProvider: BacktestMemberIdProvider,
) : GetBacktestResultUseCase {
    override fun execute(query: GetBacktestResultQuery): GetBacktestResultResult? =
        backtestRunReader.findById(query.backtestRunId)?.let { run ->
            val version = lookupStrategyVersionForBacktestUseCase.execute(
                LookupStrategyVersionForBacktestQuery(run.strategyVersionId.value),
            ) ?: return null
            if (version.ownerMemberId != backtestMemberIdProvider.currentMemberId()) return null
            val result = if (run.status == BacktestRunStatus.COMPLETED) {
                requireNotNull(backtestResultReader.findByBacktestRunId(run.id)) {
                    "COMPLETED BacktestRun requires a BacktestResult"
                }.also { backtestResult ->
                    require(backtestResult.backtestRunId == run.id) {
                        "BacktestResult belongs to another BacktestRun"
                    }
                }
            } else {
                null
            }

            GetBacktestResultResult(
                id = run.id,
                strategyId = run.strategyId,
                strategyVersionId = run.strategyVersionId,
                requestedPeriod = run.requestedPeriod,
                feeModel = run.feeModel,
                status = run.status,
                actualPeriod = run.actualPeriod,
                datasetSnapshotId = run.datasetSnapshotId,
                engineVersion = run.engineVersion,
                result = result,
                failureReason = run.failureReason,
                createdAt = run.createdAt,
            )
        }
}
