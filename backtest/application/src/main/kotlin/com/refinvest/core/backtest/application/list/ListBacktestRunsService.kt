package com.refinvest.core.backtest.application.list

import com.refinvest.core.backtest.port.inbound.list.BacktestRunSummary
import com.refinvest.core.backtest.port.inbound.list.ListBacktestRunsQuery
import com.refinvest.core.backtest.port.inbound.list.ListBacktestRunsResult
import com.refinvest.core.backtest.port.inbound.list.ListBacktestRunsUseCase
import com.refinvest.core.backtest.port.outbound.persistence.BacktestRunReader
import com.refinvest.core.backtest.port.outbound.member.BacktestMemberIdProvider
import com.refinvest.core.strategy.port.inbound.owner.LookupStrategyOwnerQuery
import com.refinvest.core.strategy.port.inbound.owner.LookupStrategyOwnerUseCase
import org.springframework.stereotype.Service

@Service
class ListBacktestRunsService(
    private val backtestRunReader: BacktestRunReader,
    private val lookupStrategyOwnerUseCase: LookupStrategyOwnerUseCase,
    private val backtestMemberIdProvider: BacktestMemberIdProvider,
) : ListBacktestRunsUseCase {
    override fun execute(query: ListBacktestRunsQuery): ListBacktestRunsResult {
        val strategyOwner = lookupStrategyOwnerUseCase.execute(LookupStrategyOwnerQuery(query.strategyId.value))
        if (strategyOwner?.ownerMemberId != backtestMemberIdProvider.currentMemberId()) {
            throw NoSuchElementException("Strategy not found")
        }
        val backtestRuns = backtestRunReader.findByStrategyId(query.strategyId, query.page, query.size)

        return ListBacktestRunsResult(
            items = backtestRuns.items.map { run ->
                BacktestRunSummary(
                    id = run.id,
                    strategyId = run.strategyId,
                    strategyVersionId = run.strategyVersionId,
                    requestedPeriod = run.requestedPeriod,
                    feeModel = run.feeModel,
                    status = run.status,
                    createdAt = run.createdAt,
                )
            },
            page = query.page,
            size = query.size,
            total = backtestRuns.total,
        )
    }
}
