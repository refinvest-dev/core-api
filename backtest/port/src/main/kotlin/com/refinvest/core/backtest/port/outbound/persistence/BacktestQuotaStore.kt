package com.refinvest.core.backtest.port.outbound.persistence

import com.refinvest.core.backtest.domain.valueobject.BacktestRunId

interface BacktestQuotaStore {
    /** Must commit or roll back with the Run and its durable dispatch. */
    fun reserve(request: BacktestQuotaRequest): BacktestQuotaReservation

    /** Idempotently releases only this Run's capacity; monthly usage is retained. */
    fun releaseConcurrentCapacity(backtestRunId: BacktestRunId)
}

enum class BacktestQuotaReservation {
    RESERVED,
    MONTHLY_LIMIT_EXCEEDED,
    CONCURRENCY_LIMIT_EXCEEDED,
}
