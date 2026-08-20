package com.refinvest.core.backtest.port.inbound.backtest.execution

import com.refinvest.core.backtest.domain.valueobject.BacktestRunId

data class FailBacktestRunCommand(
    override val backtestRunId: BacktestRunId,
    val failureReason: String,
) : RecordBacktestRunExecutionCommand
