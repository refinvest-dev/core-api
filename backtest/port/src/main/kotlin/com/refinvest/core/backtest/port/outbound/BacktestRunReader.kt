package com.refinvest.core.backtest.port.outbound

import com.refinvest.core.backtest.domain.valueobject.BacktestRunId

fun interface BacktestRunReader {
    fun findById(id: BacktestRunId): BacktestRunReadModel?
}
