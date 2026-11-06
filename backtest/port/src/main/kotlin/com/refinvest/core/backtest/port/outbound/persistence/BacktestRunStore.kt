package com.refinvest.core.backtest.port.outbound.persistence

import com.refinvest.core.backtest.domain.BacktestRun
import com.refinvest.core.backtest.domain.valueobject.BacktestRunId

interface BacktestRunStore {
    /** Save a new run or a run loaded for change in the same command transaction. */
    fun save(backtestRun: BacktestRun)

    /** Locks the aggregate until the enclosing command transaction ends. */
    fun findById(id: BacktestRunId): BacktestRun?
}