package com.refinvest.core.backtest.adapter.out.persistence

import com.refinvest.core.backtest.domain.valueobject.BacktestRunId
import com.refinvest.core.backtest.domain.valueobject.BacktestRunStatus
import com.refinvest.core.backtest.domain.valueobject.DatasetSnapshotId
import com.refinvest.core.backtest.domain.valueobject.EngineVersion
import com.refinvest.core.backtest.domain.valueobject.FeeModel
import com.refinvest.core.backtest.domain.valueobject.Percent
import com.refinvest.core.backtest.domain.valueobject.Period
import com.refinvest.core.backtest.domain.valueobject.StrategyVersionId
import com.refinvest.core.backtest.domain.valueobject.StrategyId
import com.refinvest.core.backtest.port.outbound.BacktestRunReadModel
import com.refinvest.core.backtest.port.outbound.BacktestRunPageReadModel
import com.refinvest.core.backtest.port.outbound.BacktestRunReader
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository

@Repository
class JpaBacktestRunReaderAdapter(
    private val backtestRunJpaReader: BacktestRunJpaReader,
) : BacktestRunReader {
    override fun findById(id: BacktestRunId): BacktestRunReadModel? =
        backtestRunJpaReader.findById(id.value)?.toReadModel()

    override fun findByStrategyId(
        strategyId: StrategyId,
        page: Int,
        size: Int,
    ): BacktestRunPageReadModel = backtestRunJpaReader
        .findAllByStrategyIdOrderByCreatedAtDesc(strategyId.value, PageRequest.of(page, size))
        .let { runs -> BacktestRunPageReadModel(runs.content.map { it.toReadModel() }, runs.totalElements) }

    private fun BacktestRunJpaEntity.toReadModel(): BacktestRunReadModel =
        BacktestRunReadModel(
            id = BacktestRunId(id),
            strategyId = StrategyId(strategyId),
            strategyVersionId = StrategyVersionId(strategyVersionId),
            requestedPeriod = Period(requestedPeriodStart, requestedPeriodEnd),
            feeModel = FeeModel(Percent(commission), Percent(slippage)),
            status = BacktestRunStatus.valueOf(status.name),
            actualPeriod = actualPeriodStart?.let { start ->
                Period(start, requireNotNull(actualPeriodEnd))
            },
            datasetSnapshotId = datasetSnapshotId?.let(::DatasetSnapshotId),
            engineVersion = engineVersion?.let(::EngineVersion),
            failureReason = failureReason,
            createdAt = createdAt,
        )
}
