package com.refinvest.core.backtest.port.inbound.backtest.run

import com.refinvest.core.backtest.domain.BacktestRunId

data class RunBacktestResult(
    val id: BacktestRunId,
)
