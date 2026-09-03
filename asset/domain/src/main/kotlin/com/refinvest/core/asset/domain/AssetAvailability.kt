package com.refinvest.core.asset.domain

import java.time.Instant
import java.time.LocalDate

data class AssetAvailability(
    val symbol: String,
    val inceptionDate: LocalDate,
    val dataAvailability: DataAvailability,
    val datasetSnapshotId: String,
    val snapshotCreatedAt: Instant,
)
