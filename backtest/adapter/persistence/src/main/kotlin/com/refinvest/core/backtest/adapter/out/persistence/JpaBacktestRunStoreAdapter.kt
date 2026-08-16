package com.refinvest.core.backtest.adapter.out.persistence

import com.refinvest.core.backtest.domain.BacktestRun
import com.refinvest.core.backtest.domain.BacktestRunStatus
import com.refinvest.core.backtest.port.outbound.BacktestRunStore
import org.springframework.stereotype.Repository

@Repository
class JpaBacktestRunStoreAdapter(
    private val backtestRunJpaStore: BacktestRunJpaStore,
) : BacktestRunStore {
    override fun save(backtestRun: BacktestRun) {
        backtestRunJpaStore.save(backtestRun.toEntity())
    }

    private fun BacktestRun.toEntity(): BacktestRunJpaEntity {
        check(status == BacktestRunStatus.PENDING) { "Only pending backtest runs can be persisted in this slice" }
        return BacktestRunJpaEntity(
            id = id.value,
            strategyVersionId = strategyVersionId.value,
            requestedPeriodStart = requestedPeriod.start,
            requestedPeriodEnd = requestedPeriod.end,
            commission = feeModel.commission.value,
            slippage = feeModel.slippage.value,
            status = BacktestRunStatusJpa.PENDING,
            createdAt = createdAt,
        )
    }
}
