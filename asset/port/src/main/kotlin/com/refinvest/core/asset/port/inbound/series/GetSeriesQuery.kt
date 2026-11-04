package com.refinvest.core.asset.port.inbound.series

import com.refinvest.core.asset.port.common.series.SeriesMetric
import java.time.LocalDate

data class GetSeriesQuery(
    val symbols: List<String>,
    val metric: SeriesMetric,
    val start: LocalDate,
    val end: LocalDate,
    val datasetSnapshotId: String?,
)
