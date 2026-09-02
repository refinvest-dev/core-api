package com.refinvest.core.backtest.domain

import com.refinvest.core.common.domain.AggregateRoot
import com.refinvest.core.backtest.domain.backtest.BacktestResult
import com.refinvest.core.backtest.domain.valueobject.BacktestRunId
import com.refinvest.core.backtest.domain.valueobject.BacktestRunStatus
import com.refinvest.core.backtest.domain.valueobject.DatasetSnapshotId
import com.refinvest.core.backtest.domain.valueobject.EngineVersion
import com.refinvest.core.backtest.domain.valueobject.FeeModel
import com.refinvest.core.backtest.domain.valueobject.Period
import com.refinvest.core.backtest.domain.valueobject.StrategyVersionId
import com.refinvest.core.backtest.domain.valueobject.StrategyId
import java.time.Instant

class BacktestRun private constructor(
    id: BacktestRunId,
    val strategyId: StrategyId,
    val strategyVersionId: StrategyVersionId,
    val requestedPeriod: Period,
    val feeModel: FeeModel,
    val createdAt: Instant,
    status: BacktestRunStatus,
    actualPeriod: Period?,
    datasetSnapshotId: DatasetSnapshotId?,
    engineVersion: EngineVersion?,
    result: BacktestResult?,
    failureReason: String?,
) : AggregateRoot<BacktestRunId>(id) {
    var status: BacktestRunStatus = status
        private set
    var actualPeriod: Period? = actualPeriod
        private set
    var datasetSnapshotId: DatasetSnapshotId? = datasetSnapshotId
        private set
    var engineVersion: EngineVersion? = engineVersion
        private set
    var result: BacktestResult? = result
        private set
    var failureReason: String? = failureReason
        private set

    init {
        validateState()
    }

    fun start(
        actualPeriod: Period,
        datasetSnapshotId: DatasetSnapshotId,
        engineVersion: EngineVersion,
    ) {
        require(status == BacktestRunStatus.PENDING) { "BacktestRun can start only from PENDING" }
        status = BacktestRunStatus.RUNNING
        this.actualPeriod = actualPeriod
        this.datasetSnapshotId = datasetSnapshotId
        this.engineVersion = engineVersion
        validateState()
    }

    fun complete(result: BacktestResult) {
        require(status == BacktestRunStatus.RUNNING) { "BacktestRun can complete only from RUNNING" }
        require(result.backtestRunId == id) { "BacktestResult belongs to another BacktestRun" }
        require(result.dataIntegrityStatus.datasetSnapshotId == datasetSnapshotId) {
            "BacktestResult datasetSnapshotId must match BacktestRun"
        }
        status = BacktestRunStatus.COMPLETED
        this.result = result
        validateState()
    }

    fun fail(failureReason: String) {
        require(status == BacktestRunStatus.RUNNING) { "BacktestRun can fail only from RUNNING" }
        require(failureReason.isNotBlank()) { "failureReason must not be blank" }
        status = BacktestRunStatus.FAILED
        this.failureReason = failureReason
        validateState()
    }

    /** Records a Compute request rejection before Compute has started execution. */
    fun failBeforeExecution(failureReason: String) {
        require(status == BacktestRunStatus.PENDING) { "BacktestRun can be rejected only from PENDING" }
        require(failureReason.isNotBlank()) { "failureReason must not be blank" }
        status = BacktestRunStatus.FAILED
        this.failureReason = failureReason
        validateState()
    }

    private fun validateState() {
        when (status) {
            BacktestRunStatus.PENDING -> {
                require(actualPeriod == null) { "PENDING BacktestRun must not have actualPeriod" }
                require(datasetSnapshotId == null) { "PENDING BacktestRun must not have datasetSnapshotId" }
                require(engineVersion == null) { "PENDING BacktestRun must not have engineVersion" }
                require(result == null) { "PENDING BacktestRun must not have a result" }
                require(failureReason == null) { "PENDING BacktestRun must not have a failureReason" }
            }
            BacktestRunStatus.RUNNING -> {
                require(actualPeriod != null) { "RUNNING BacktestRun requires actualPeriod" }
                require(datasetSnapshotId != null) { "RUNNING BacktestRun requires datasetSnapshotId" }
                require(engineVersion != null) { "RUNNING BacktestRun requires engineVersion" }
                require(result == null) { "RUNNING BacktestRun must not have a result" }
                require(failureReason == null) { "RUNNING BacktestRun must not have a failureReason" }
            }
            BacktestRunStatus.COMPLETED -> {
                require(actualPeriod != null && datasetSnapshotId != null && engineVersion != null) {
                    "COMPLETED BacktestRun requires Compute execution metadata"
                }
                require(result != null) { "COMPLETED BacktestRun requires a BacktestResult" }
                require(failureReason == null) { "COMPLETED BacktestRun must not have a failureReason" }
            }
            BacktestRunStatus.FAILED -> {
                val hasExecutionMetadata = actualPeriod != null && datasetSnapshotId != null && engineVersion != null
                val hasNoExecutionMetadata = actualPeriod == null && datasetSnapshotId == null && engineVersion == null
                require(hasExecutionMetadata || hasNoExecutionMetadata) {
                    "FAILED BacktestRun requires either complete or absent Compute execution metadata"
                }
                require(result == null) { "FAILED BacktestRun must not have a result" }
                require(!failureReason.isNullOrBlank()) { "FAILED BacktestRun requires a failureReason" }
            }
        }
    }

    companion object {
        fun createPending(
            id: BacktestRunId,
            strategyId: StrategyId,
            strategyVersionId: StrategyVersionId,
            requestedPeriod: Period,
            feeModel: FeeModel,
            createdAt: Instant,
        ): BacktestRun = BacktestRun(
            id = id,
            strategyId = strategyId,
            strategyVersionId = strategyVersionId,
            requestedPeriod = requestedPeriod,
            feeModel = feeModel,
            createdAt = createdAt,
            status = BacktestRunStatus.PENDING,
            actualPeriod = null,
            datasetSnapshotId = null,
            engineVersion = null,
            result = null,
            failureReason = null,
        )

        fun restore(
            id: BacktestRunId,
            strategyId: StrategyId,
            strategyVersionId: StrategyVersionId,
            requestedPeriod: Period,
            feeModel: FeeModel,
            createdAt: Instant,
            status: BacktestRunStatus,
            actualPeriod: Period?,
            datasetSnapshotId: DatasetSnapshotId?,
            engineVersion: EngineVersion?,
            result: BacktestResult?,
            failureReason: String?,
        ): BacktestRun = BacktestRun(
            id = id,
            strategyId = strategyId,
            strategyVersionId = strategyVersionId,
            requestedPeriod = requestedPeriod,
            feeModel = feeModel,
            createdAt = createdAt,
            status = status,
            actualPeriod = actualPeriod,
            datasetSnapshotId = datasetSnapshotId,
            engineVersion = engineVersion,
            result = result,
            failureReason = failureReason,
        )
    }
}
