package com.refinvest.core.backtest.port.inbound.execution

import com.refinvest.core.backtest.domain.valueobject.BacktestRunId

data class FailBacktestRunCommand(
    override val backtestRunId: BacktestRunId,
    val failureReason: String,
    val errorCode: String? = null,
) : RecordBacktestRunExecutionCommand
