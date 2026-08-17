package com.refinvest.core.backtest.domain.backtest

import com.refinvest.core.backtest.domain.valueobject.DatasetSnapshotId

data class DataIntegrityStatus(
    val datasetSnapshotId: DatasetSnapshotId,
    val corporateActionsApplied: Boolean,
    val pointInTimeValidationPassed: Boolean,
)
