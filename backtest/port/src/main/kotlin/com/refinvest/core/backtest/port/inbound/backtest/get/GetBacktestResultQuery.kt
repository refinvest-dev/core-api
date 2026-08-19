package com.refinvest.core.backtest.port.inbound.backtest.get

import com.refinvest.core.backtest.domain.valueobject.BacktestRunId

data class GetBacktestResultQuery(
    val backtestRunId: BacktestRunId,
)
