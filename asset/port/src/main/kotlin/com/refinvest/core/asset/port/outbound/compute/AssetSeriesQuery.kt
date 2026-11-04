package com.refinvest.core.asset.port.outbound.compute

import com.refinvest.core.asset.port.common.series.SeriesMetric
import java.time.LocalDate

data class AssetSeriesQuery(
    val symbols: List<String>,
    val metric: SeriesMetric,
    val start: LocalDate,
    val end: LocalDate,
    val datasetSnapshotId: String?,
)
