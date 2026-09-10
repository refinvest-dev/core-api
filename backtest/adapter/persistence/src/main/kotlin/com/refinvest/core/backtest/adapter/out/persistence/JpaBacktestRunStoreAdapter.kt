package com.refinvest.core.backtest.adapter.out.persistence

import com.refinvest.core.backtest.domain.BacktestRun
import com.refinvest.core.backtest.domain.valueobject.BacktestRunId
import com.refinvest.core.backtest.domain.valueobject.BacktestRunStatus
import com.refinvest.core.backtest.domain.valueobject.DatasetSnapshotId
import com.refinvest.core.backtest.domain.valueobject.EngineVersion
import com.refinvest.core.backtest.domain.valueobject.FeeModel
import com.refinvest.core.backtest.domain.valueobject.Percent
import com.refinvest.core.backtest.domain.valueobject.Period
import com.refinvest.core.backtest.domain.valueobject.StrategyId
import com.refinvest.core.backtest.domain.valueobject.StrategyVersionId
import com.refinvest.core.backtest.port.outbound.persistence.BacktestRunStore
import org.springframework.stereotype.Repository

@Repository
class JpaBacktestRunStoreAdapter(
    private val backtestRunJpaStore: BacktestRunJpaStore,
    private val backtestResultJpaStore: BacktestResultJpaStore,
    private val resultPayloadMapper: BacktestResultPayloadMapper,
) : BacktestRunStore {
    override fun save(backtestRun: BacktestRun) {
        backtestRunJpaStore.save(backtestRun.toEntity())
        backtestRun.result?.let { result ->
            backtestResultJpaStore.save(
                BacktestResultJpaEntity(
                    backtestRunId = backtestRun.id.value,
                    resultPayload = resultPayloadMapper.serialize(result),
                ),
            )
        }
    }

    override fun findById(id: BacktestRunId): BacktestRun? =
        backtestRunJpaStore.findById(id.value).orElse(null)?.toDomain()

    private fun BacktestRun.toEntity(): BacktestRunJpaEntity {
        return BacktestRunJpaEntity(
            id = id.value,
            strategyId = strategyId.value,
            strategyVersionId = strategyVersionId.value,
            requestedPeriodStart = requestedPeriod.start,
            requestedPeriodEnd = requestedPeriod.end,
            commission = feeModel.commission.value,
            slippage = feeModel.slippage.value,
            status = BacktestRunStatusJpa.valueOf(status.name),
            actualPeriodStart = actualPeriod?.start,
            actualPeriodEnd = actualPeriod?.end,
            datasetSnapshotId = datasetSnapshotId?.value,
            engineVersion = engineVersion?.value,
            failureReason = failureReason,
            errorCode = errorCode,
            createdAt = createdAt,
        )
    }

    private fun BacktestRunJpaEntity.toDomain(): BacktestRun {
        val runId = BacktestRunId(id)
        val result = backtestResultJpaStore.findById(runId.value)
            .map { entity -> resultPayloadMapper.deserialize(entity.resultPayload) }
            .orElse(null)
        return BacktestRun.restore(
            id = runId,
            strategyId = StrategyId(strategyId),
            strategyVersionId = StrategyVersionId(strategyVersionId),
            requestedPeriod = Period(requestedPeriodStart, requestedPeriodEnd),
            feeModel = FeeModel(Percent(commission), Percent(slippage)),
            createdAt = createdAt,
            status = BacktestRunStatus.valueOf(status.name),
            actualPeriod = actualPeriodStart?.let { start -> Period(start, requireNotNull(actualPeriodEnd)) },
            datasetSnapshotId = datasetSnapshotId?.let(::DatasetSnapshotId),
            engineVersion = engineVersion?.let(::EngineVersion),
            result = result,
            failureReason = failureReason,
            errorCode = errorCode,
        )
    }
}
