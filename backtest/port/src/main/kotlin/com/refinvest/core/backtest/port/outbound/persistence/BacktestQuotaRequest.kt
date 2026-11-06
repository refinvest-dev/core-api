package com.refinvest.core.backtest.port.outbound.persistence

import com.refinvest.core.backtest.domain.valueobject.BacktestRunId
import com.refinvest.core.shared.kernel.member.MemberId
import java.time.YearMonth

data class BacktestQuotaRequest(
    val memberId: MemberId,
    val backtestRunId: BacktestRunId,
    val quotaMonth: YearMonth,
    val monthlyExecutionLimit: Int,
    val maxConcurrentRuns: Int,
) {
    init {
        require(monthlyExecutionLimit > 0 && maxConcurrentRuns > 0)
    }
}
