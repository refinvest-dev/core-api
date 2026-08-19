package com.refinvest.core.backtest.adapter.web.backtest.get

import com.refinvest.core.backtest.domain.backtest.DataIntegrityStatus

data class DataIntegrityStatusResponse(
    val datasetSnapshotId: String,
    val corporateActionsApplied: Boolean,
    val pointInTimeValidationPassed: Boolean,
) {
    companion object {
        fun from(status: DataIntegrityStatus): DataIntegrityStatusResponse = DataIntegrityStatusResponse(
            status.datasetSnapshotId.value,
            status.corporateActionsApplied,
            status.pointInTimeValidationPassed,
        )
    }
}
