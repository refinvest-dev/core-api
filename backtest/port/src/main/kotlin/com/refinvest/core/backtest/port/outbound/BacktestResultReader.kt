package com.refinvest.core.backtest.port.outbound

import com.refinvest.core.backtest.domain.backtest.BacktestResult
import com.refinvest.core.backtest.domain.valueobject.BacktestRunId

fun interface BacktestResultReader {
    fun findByBacktestRunId(backtestRunId: BacktestRunId): BacktestResult?
}
