package com.refinvest.core.backtest.port.inbound.execution

import com.refinvest.core.backtest.domain.valueobject.BacktestRunId

sealed interface RecordBacktestRunExecutionCommand {
    val backtestRunId: BacktestRunId
}
