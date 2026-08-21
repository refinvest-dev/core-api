package com.refinvest.core.backtest.port.outbound

import com.refinvest.core.shared.kernel.member.MemberId
import java.time.YearMonth

fun interface BacktestQuotaStore {
    fun reserve(
        memberId: MemberId,
        quotaMonth: YearMonth,
        monthlyExecutionLimit: Int,
        maxConcurrentRuns: Int,
    ): BacktestQuotaReservation

    fun releaseConcurrentCapacity(memberId: MemberId, quotaMonth: YearMonth) = Unit
}

enum class BacktestQuotaReservation {
    RESERVED,
    MONTHLY_LIMIT_EXCEEDED,
    CONCURRENCY_LIMIT_EXCEEDED,
}
