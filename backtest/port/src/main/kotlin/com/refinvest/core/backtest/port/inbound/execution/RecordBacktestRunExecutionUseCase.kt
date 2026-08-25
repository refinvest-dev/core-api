package com.refinvest.core.backtest.port.inbound.execution

import com.refinvest.core.backtest.domain.valueobject.BacktestRunId

fun interface RecordBacktestRunExecutionUseCase {
    fun execute(command: RecordBacktestRunExecutionCommand): BacktestRunId
}
