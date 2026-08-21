package com.refinvest.core.backtest.port.inbound.backtest.usage

import com.refinvest.core.shared.kernel.member.MemberId
import java.time.Instant

data class GetMonthlyBacktestUsageQuery(
    val memberId: MemberId,
    val startInclusive: Instant,
    val endExclusive: Instant,
)
