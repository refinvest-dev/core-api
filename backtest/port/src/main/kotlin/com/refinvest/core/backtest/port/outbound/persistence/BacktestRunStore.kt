package com.refinvest.core.backtest.port.outbound.persistence

import com.refinvest.core.backtest.domain.BacktestRun
import com.refinvest.core.backtest.domain.valueobject.BacktestRunId

interface BacktestRunStore {
    fun save(backtestRun: BacktestRun)

    fun findById(id: BacktestRunId): BacktestRun?
}