package com.refinvest.core.backtest.port.inbound.backtest.poll

import com.refinvest.core.backtest.domain.valueobject.BacktestRunId

data class PollBacktestStatusQuery(
    val backtestRunId: BacktestRunId,
)
