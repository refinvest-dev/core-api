package com.refinvest.core.backtest.port.inbound.execution

import com.refinvest.core.backtest.domain.backtest.BacktestResult
import com.refinvest.core.backtest.domain.valueobject.BacktestRunId

data class CompleteBacktestRunCommand(
    override val backtestRunId: BacktestRunId,
    val result: BacktestResult,
) : RecordBacktestRunExecutionCommand
