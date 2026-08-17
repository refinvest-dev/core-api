package com.refinvest.core.backtest.domain.valueobject

@JvmInline
value class DatasetSnapshotId(val value: String) {
    init {
        require(value.isNotBlank()) { "datasetSnapshotId must not be blank" }
    }
}
