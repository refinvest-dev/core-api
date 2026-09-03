package com.refinvest.core.asset.domain

import java.time.Instant

data class SeriesSnapshot(
    val datasetSnapshotId: String,
    val snapshotCreatedAt: Instant,
    val adjustmentPolicy: String,
    val series: List<AssetSeries>,
)
