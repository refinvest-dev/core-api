package com.refinvest.core.asset.adapter.web.asset.series

import com.refinvest.core.asset.port.inbound.series.GetSeriesResult
import java.time.Instant

data class AssetSeriesResponse(
    val datasetSnapshotId: String,
    val snapshotCreatedAt: Instant,
    val adjustmentPolicy: String,
    val series: List<SeriesResponse>,
) {
    companion object {
        fun from(result: GetSeriesResult) = AssetSeriesResponse(
            datasetSnapshotId = result.snapshot.datasetSnapshotId,
            snapshotCreatedAt = result.snapshot.snapshotCreatedAt,
            adjustmentPolicy = result.snapshot.adjustmentPolicy,
            series = result.snapshot.series.map(SeriesResponse::from),
        )
    }
}
