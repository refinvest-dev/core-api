package com.refinvest.core.backtest.port.inbound.backtest.execution

import com.refinvest.core.backtest.domain.valueobject.BacktestRunId
import com.refinvest.core.backtest.domain.valueobject.DatasetSnapshotId
import com.refinvest.core.backtest.domain.valueobject.EngineVersion
import com.refinvest.core.backtest.domain.valueobject.Period

data class StartBacktestRunCommand(
    override val backtestRunId: BacktestRunId,
    val actualPeriod: Period,
    val datasetSnapshotId: DatasetSnapshotId,
    val engineVersion: EngineVersion,
) : RecordBacktestRunExecutionCommand
