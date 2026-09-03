package com.refinvest.core.asset.domain

data class AssetSeries(
    val symbol: String,
    val points: List<SeriesPoint>,
)
