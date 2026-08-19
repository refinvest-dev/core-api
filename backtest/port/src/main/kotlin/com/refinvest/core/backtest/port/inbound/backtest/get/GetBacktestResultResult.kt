package com.refinvest.core.backtest.port.inbound.backtest.get

import com.refinvest.core.backtest.domain.backtest.BacktestResult
import com.refinvest.core.backtest.domain.valueobject.BacktestRunId
import com.refinvest.core.backtest.domain.valueobject.BacktestRunStatus
import com.refinvest.core.backtest.domain.valueobject.DatasetSnapshotId
import com.refinvest.core.backtest.domain.valueobject.EngineVersion
import com.refinvest.core.backtest.domain.valueobject.FeeModel
import com.refinvest.core.backtest.domain.valueobject.Period
import com.refinvest.core.backtest.domain.valueobject.StrategyId
import com.refinvest.core.backtest.domain.valueobject.StrategyVersionId
import java.time.Instant

data class GetBacktestResultResult(
    val id: BacktestRunId,
    val strategyId: StrategyId,
    val strategyVersionId: StrategyVersionId,
    val requestedPeriod: Period,
    val feeModel: FeeModel,
    val status: BacktestRunStatus,
    val actualPeriod: Period?,
    val datasetSnapshotId: DatasetSnapshotId?,
    val engineVersion: EngineVersion?,
    val result: BacktestResult?,
    val failureReason: String?,
    val createdAt: Instant,
)
