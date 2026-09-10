package com.refinvest.core.backtest.port.outbound.persistence

import com.refinvest.core.backtest.domain.valueobject.BacktestRunId
import com.refinvest.core.backtest.domain.valueobject.StrategyId
import com.refinvest.core.backtest.domain.valueobject.StrategyVersionId
import com.refinvest.core.shared.kernel.member.MemberId
import java.time.Instant

interface BacktestRunReader {
    fun findById(id: BacktestRunId): BacktestRunReadModel?
    fun findLatestCompletedByStrategyVersionId(strategyVersionId: StrategyVersionId): BacktestRunReadModel? = null
    fun findLatestTerminalByStrategyVersionId(strategyVersionId: StrategyVersionId): BacktestRunReadModel? = null

    fun findByStrategyId(
        strategyId: StrategyId,
        page: Int,
        size: Int,
    ): BacktestRunPageReadModel

    fun countByMemberIdAndCreatedAtBetween(
        memberId: MemberId,
        startInclusive: Instant,
        endExclusive: Instant,
    ): Long
}
