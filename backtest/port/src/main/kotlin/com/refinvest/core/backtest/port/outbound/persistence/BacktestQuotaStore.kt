package com.refinvest.core.backtest.port.outbound.persistence

import com.refinvest.core.shared.kernel.member.MemberId
import java.time.YearMonth

interface BacktestQuotaStore {
    fun reserve(
        memberId: MemberId,
        quotaMonth: YearMonth,
        monthlyExecutionLimit: Int,
        maxConcurrentRuns: Int,
    ): BacktestQuotaReservation

    fun releaseConcurrentCapacity(memberId: MemberId, quotaMonth: YearMonth)
}

enum class BacktestQuotaReservation {
    RESERVED,
    MONTHLY_LIMIT_EXCEEDED,
    CONCURRENCY_LIMIT_EXCEEDED,
}
