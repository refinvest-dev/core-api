package com.refinvest.core.backtest.adapter.out.persistence

import com.refinvest.core.backtest.domain.valueobject.BacktestRunId
import com.refinvest.core.backtest.domain.valueobject.StrategyVersionId
import com.refinvest.core.backtest.domain.valueobject.StrategyId
import com.refinvest.core.backtest.port.outbound.persistence.BacktestRunReadModel
import com.refinvest.core.backtest.port.outbound.persistence.BacktestRunPageReadModel
import com.refinvest.core.backtest.port.outbound.persistence.BacktestRunReader
import com.refinvest.core.shared.kernel.member.MemberId
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class JpaBacktestRunReaderAdapter(
    private val backtestRunJpaReader: BacktestRunJpaReader,
) : BacktestRunReader {
    override fun findById(id: BacktestRunId): BacktestRunReadModel? =
        backtestRunJpaReader.findById(id.value)?.let(BacktestRunReadModelMapper::toReadModel)

    override fun findLatestCompletedByStrategyVersionId(strategyVersionId: StrategyVersionId): BacktestRunReadModel? =
        backtestRunJpaReader.findFirstByStrategyVersionIdAndStatusOrderByCreatedAtDesc(strategyVersionId.value, BacktestRunStatusJpa.COMPLETED)?.let(BacktestRunReadModelMapper::toReadModel)

    override fun findLatestTerminalByStrategyVersionId(strategyVersionId: StrategyVersionId): BacktestRunReadModel? =
        backtestRunJpaReader.findFirstByStrategyVersionIdAndStatusInOrderByCreatedAtDesc(
            strategyVersionId.value,
            TERMINAL_STATUSES,
        )?.let(BacktestRunReadModelMapper::toReadModel)

    override fun findByStrategyId(
        strategyId: StrategyId,
        page: Int,
        size: Int,
    ): BacktestRunPageReadModel = backtestRunJpaReader
        .findAllByStrategyIdOrderByCreatedAtDesc(strategyId.value, PageRequest.of(page, size))
        .let { runs -> BacktestRunPageReadModel(runs.content.map(BacktestRunReadModelMapper::toReadModel), runs.totalElements) }

    override fun countByMemberIdAndCreatedAtBetween(
        memberId: MemberId,
        startInclusive: Instant,
        endExclusive: Instant,
    ): Long = backtestRunJpaReader.countByMemberIdAndCreatedAtBetween(
        memberId = memberId.value,
        startInclusive = startInclusive,
        endExclusive = endExclusive,
    )

    private companion object {
        val TERMINAL_STATUSES = listOf(BacktestRunStatusJpa.COMPLETED, BacktestRunStatusJpa.FAILED)
    }
}
