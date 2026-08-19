package com.refinvest.core.backtest.adapter.out.persistence

import com.refinvest.core.backtest.domain.valueobject.BacktestRunId
import com.refinvest.core.backtest.domain.valueobject.BacktestRunStatus
import com.refinvest.core.backtest.domain.valueobject.FeeModel
import com.refinvest.core.backtest.domain.valueobject.Percent
import com.refinvest.core.backtest.domain.valueobject.Period
import com.refinvest.core.backtest.domain.valueobject.StrategyVersionId
import com.refinvest.core.backtest.domain.valueobject.StrategyId
import com.refinvest.core.backtest.port.outbound.BacktestRunReadModel
import com.refinvest.core.backtest.port.outbound.BacktestRunReader
import org.springframework.stereotype.Repository

@Repository
class JpaBacktestRunReaderAdapter(
    private val backtestRunJpaReader: BacktestRunJpaReader,
) : BacktestRunReader {
    override fun findById(id: BacktestRunId): BacktestRunReadModel? =
        backtestRunJpaReader.findById(id.value)?.let { run ->
            BacktestRunReadModel(
                id = BacktestRunId(run.id),
                strategyId = StrategyId(run.strategyId),
                strategyVersionId = StrategyVersionId(run.strategyVersionId),
                requestedPeriod = Period(run.requestedPeriodStart, run.requestedPeriodEnd),
                feeModel = FeeModel(Percent(run.commission), Percent(run.slippage)),
                status = BacktestRunStatus.valueOf(run.status.name),
                createdAt = run.createdAt,
            )
        }
}
