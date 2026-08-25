package com.refinvest.core.backtest.port.outbound.id

import com.refinvest.core.backtest.domain.valueobject.BacktestRunId

fun interface BacktestRunIdGenerator {
    fun next(): BacktestRunId
}
