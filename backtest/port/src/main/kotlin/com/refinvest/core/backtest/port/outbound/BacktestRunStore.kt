package com.refinvest.core.backtest.port.outbound

import com.refinvest.core.backtest.domain.BacktestRun
import com.refinvest.core.backtest.domain.valueobject.BacktestRunId

fun interface BacktestRunStore {
    fun save(backtestRun: BacktestRun)
}

fun interface BacktestRunIdGenerator {
    fun next(): BacktestRunId
}
