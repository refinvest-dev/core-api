package com.refinvest.core.backtest.port.outbound.compute

import com.refinvest.core.backtest.domain.backtest.BacktestResult
import com.refinvest.core.backtest.domain.valueobject.BacktestRunId
import com.refinvest.core.backtest.domain.valueobject.DatasetSnapshotId
import com.refinvest.core.backtest.domain.valueobject.EngineVersion
import com.refinvest.core.backtest.domain.valueobject.Period
import java.time.Duration

fun interface ComputeBacktestStatusClient {
    /** Maps a Compute result to the Core run that owns the durable dispatch. */
    fun getBacktestStatus(computeRunId: String, backtestRunId: BacktestRunId): ComputeBacktestStatusLookup
}

sealed interface ComputeBacktestStatusLookup {
    data class Found(val status: ComputeBacktestStatus) : ComputeBacktestStatusLookup

    data object NotFound : ComputeBacktestStatusLookup

    data class RetryLater(val retryAfter: Duration) : ComputeBacktestStatusLookup
}

data class ComputeBacktestStatus(
    val status: ComputeBacktestStatusValue,
    val actualPeriod: Period? = null,
    val datasetSnapshotId: DatasetSnapshotId? = null,
    val engineVersion: EngineVersion? = null,
    val result: BacktestResult? = null,
    val failureReason: String? = null,
    val errorCode: String? = null,
) {
    fun hasExecutionMetadata(): Boolean =
        actualPeriod != null && datasetSnapshotId != null && engineVersion != null

    fun requireExecutionMetadata(): ComputeExecutionMetadata = ComputeExecutionMetadata(
        actualPeriod = requireNotNull(actualPeriod) { "Compute status requires actualPeriod" },
        datasetSnapshotId = requireNotNull(datasetSnapshotId) { "Compute status requires datasetSnapshotId" },
        engineVersion = requireNotNull(engineVersion) { "Compute status requires engineVersion" },
    )
}

data class ComputeExecutionMetadata(
    val actualPeriod: Period,
    val datasetSnapshotId: DatasetSnapshotId,
    val engineVersion: EngineVersion,
)

enum class ComputeBacktestStatusValue {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
}
