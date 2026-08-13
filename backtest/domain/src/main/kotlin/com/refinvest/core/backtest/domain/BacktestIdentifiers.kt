package com.refinvest.core.backtest.domain

import com.refinvest.core.common.domain.Identifier

@JvmInline
value class BacktestRunId(override val value: Long) : Identifier<Long>

/** A reference to the Strategy bounded context; this is not Strategy's domain type. */
@JvmInline
value class StrategyVersionId(override val value: Long) : Identifier<Long>

@JvmInline
value class DatasetSnapshotId(val value: String) {
    init {
        require(value.isNotBlank()) { "datasetSnapshotId must not be blank" }
    }
}

@JvmInline
value class EngineVersion(val value: String) {
    init {
        require(value.isNotBlank()) { "engineVersion must not be blank" }
    }
}
