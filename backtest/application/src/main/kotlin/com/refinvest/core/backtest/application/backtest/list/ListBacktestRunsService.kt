package com.refinvest.core.backtest.application.backtest.list

import com.refinvest.core.backtest.port.inbound.backtest.list.BacktestRunSummary
import com.refinvest.core.backtest.port.inbound.backtest.list.ListBacktestRunsQuery
import com.refinvest.core.backtest.port.inbound.backtest.list.ListBacktestRunsResult
import com.refinvest.core.backtest.port.inbound.backtest.list.ListBacktestRunsUseCase
import com.refinvest.core.backtest.port.outbound.BacktestRunReader
import com.refinvest.core.strategy.domain.valueobject.StrategyId as StrategyIdInStrategy
import com.refinvest.core.strategy.port.inbound.strategy.get.GetStrategyQuery
import com.refinvest.core.strategy.port.inbound.strategy.get.GetStrategyUseCase
import org.springframework.stereotype.Service

@Service
class ListBacktestRunsService(
    private val backtestRunReader: BacktestRunReader,
    private val getStrategyUseCase: GetStrategyUseCase,
) : ListBacktestRunsUseCase {
    override fun execute(query: ListBacktestRunsQuery): ListBacktestRunsResult {
        getStrategyUseCase.execute(GetStrategyQuery(StrategyIdInStrategy(query.strategyId.value)))
            ?: throw NoSuchElementException("Strategy not found")
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
