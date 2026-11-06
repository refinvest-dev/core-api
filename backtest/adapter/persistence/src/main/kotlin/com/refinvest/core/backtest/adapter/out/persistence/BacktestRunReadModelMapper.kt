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
import com.refinvest.core.backtest.port.outbound.persistence.BacktestRunReadModel

internal object BacktestRunReadModelMapper {
    fun toReadModel(entity: BacktestRunJpaEntity): BacktestRunReadModel = with(entity) {
        BacktestRunReadModel(
            id = BacktestRunId(id),
            strategyId = StrategyId(strategyId),
            strategyVersionId = StrategyVersionId(strategyVersionId),
            requestedPeriod = Period(requestedPeriodStart, requestedPeriodEnd),
            feeModel = FeeModel(Percent(commission), Percent(slippage)),
            status = BacktestRunStatus.valueOf(status.name),
            actualPeriod = BacktestRunPersistenceMapper.actualPeriodOf(this),
            datasetSnapshotId = datasetSnapshotId?.let(::DatasetSnapshotId),
            engineVersion = engineVersion?.let(::EngineVersion),
            failureReason = failureReason,
            errorCode = errorCode,
            createdAt = createdAt,
        )
    }
}
