package com.refinvest.core.backtest.port.inbound.backtest.poll

import com.refinvest.core.backtest.domain.valueobject.BacktestRunId
import com.refinvest.core.backtest.domain.valueobject.BacktestRunStatus
import com.refinvest.core.backtest.domain.valueobject.FeeModel
import com.refinvest.core.backtest.domain.valueobject.Period
import com.refinvest.core.backtest.domain.valueobject.StrategyVersionId
import java.time.Instant

data class PollBacktestStatusResult(
    val id: BacktestRunId,
    val strategyVersionId: StrategyVersionId,
    val requestedPeriod: Period,
    val feeModel: FeeModel,
    val status: BacktestRunStatus,
    val createdAt: Instant,
)
