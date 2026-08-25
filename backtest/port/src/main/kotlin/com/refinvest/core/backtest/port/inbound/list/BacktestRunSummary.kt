package com.refinvest.core.backtest.port.inbound.list

import com.refinvest.core.backtest.domain.valueobject.BacktestRunId
import com.refinvest.core.backtest.domain.valueobject.BacktestRunStatus
import com.refinvest.core.backtest.domain.valueobject.FeeModel
import com.refinvest.core.backtest.domain.valueobject.Period
import com.refinvest.core.backtest.domain.valueobject.StrategyId
import com.refinvest.core.backtest.domain.valueobject.StrategyVersionId
import java.time.Instant

data class BacktestRunSummary(
    val id: BacktestRunId,
    val strategyId: StrategyId,
    val strategyVersionId: StrategyVersionId,
    val requestedPeriod: Period,
    val feeModel: FeeModel,
    val status: BacktestRunStatus,
    val createdAt: Instant,
)
