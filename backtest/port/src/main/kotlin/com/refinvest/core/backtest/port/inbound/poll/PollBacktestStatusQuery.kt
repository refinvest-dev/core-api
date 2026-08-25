package com.refinvest.core.backtest.port.inbound.poll

import com.refinvest.core.backtest.domain.valueobject.BacktestRunId

data class PollBacktestStatusQuery(
    val backtestRunId: BacktestRunId,
)
