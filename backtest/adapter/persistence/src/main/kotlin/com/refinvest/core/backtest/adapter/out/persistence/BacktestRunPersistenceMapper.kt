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

internal data class BacktestRunPersistenceState(val run: BacktestRunJpaEntity, val result: BacktestResultJpaEntity?)

internal object BacktestRunPersistenceMapper {
    fun toPersistence(run: BacktestRun, payloadMapper: BacktestResultPayloadMapper): BacktestRunPersistenceState =
        BacktestRunPersistenceState(run.toEntity(), run.result?.let { BacktestResultPersistenceMapper.toEntity(it, payloadMapper) })

    fun toDomain(entity: BacktestRunJpaEntity, resultEntity: BacktestResultJpaEntity?,
                 payloadMapper: BacktestResultPayloadMapper): BacktestRun {
        require(resultEntity == null || resultEntity.backtestRunId == entity.id) { "Result row belongs to another BacktestRun" }
        val result = resultEntity?.let { BacktestResultPersistenceMapper.toDomain(it, payloadMapper) }
        return with(entity) { BacktestRun.restore(
            id = BacktestRunId(id),
            strategyId = StrategyId(strategyId),
            strategyVersionId = StrategyVersionId(strategyVersionId),
            requestedPeriod = Period(requestedPeriodStart, requestedPeriodEnd),
            feeModel = FeeModel(Percent(commission), Percent(slippage)),
            createdAt = createdAt,
            status = BacktestRunStatus.valueOf(status.name),
            actualPeriod = actualPeriodOf(this),
            datasetSnapshotId = datasetSnapshotId?.let(::DatasetSnapshotId),
            engineVersion = engineVersion?.let(::EngineVersion),
            result = result,
            failureReason = failureReason,
            errorCode = errorCode,
        ) }
    }

    fun actualPeriodOf(entity: BacktestRunJpaEntity): Period? {
        require((entity.actualPeriodStart == null) == (entity.actualPeriodEnd == null)) { "Actual period boundaries must both be present or absent" }
        return entity.actualPeriodStart?.let { Period(it, requireNotNull(entity.actualPeriodEnd)) }
    }

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

}
