package com.refinvest.core.backtest.port.inbound.get

import com.refinvest.core.backtest.domain.valueobject.BacktestRunId

data class GetBacktestResultQuery(
    val backtestRunId: BacktestRunId,
)
