package com.refinvest.core.backtest.port.inbound.backtest.run

import com.refinvest.core.backtest.domain.BacktestRunId
import com.refinvest.core.backtest.domain.BacktestRunStatus
import com.refinvest.core.backtest.domain.FeeModel
import com.refinvest.core.backtest.domain.Period
import com.refinvest.core.backtest.domain.StrategyVersionId
import java.time.Instant

data class RunBacktestResult(
    val id: BacktestRunId,
    val strategyVersionId: StrategyVersionId,
    val requestedPeriod: Period,
    val feeModel: FeeModel,
    val status: BacktestRunStatus,
    val createdAt: Instant,
)
