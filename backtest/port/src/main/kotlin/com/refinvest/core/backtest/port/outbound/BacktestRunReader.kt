package com.refinvest.core.backtest.port.outbound

import com.refinvest.core.backtest.domain.valueobject.BacktestRunId
import com.refinvest.core.backtest.domain.valueobject.StrategyId
import com.refinvest.core.shared.kernel.member.MemberId
import java.time.Instant

fun interface BacktestRunReader {
    fun findById(id: BacktestRunId): BacktestRunReadModel?

    fun findByStrategyId(
        strategyId: StrategyId,
        page: Int,
        size: Int,
    ): BacktestRunPageReadModel = BacktestRunPageReadModel(emptyList(), 0)

    fun countByMemberIdAndCreatedAtBetween(
        memberId: MemberId,
        startInclusive: Instant,
        endExclusive: Instant,
    ): Long = 0
}
