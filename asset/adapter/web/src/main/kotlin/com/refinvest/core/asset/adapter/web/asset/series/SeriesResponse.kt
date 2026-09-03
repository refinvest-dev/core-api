package com.refinvest.core.asset.adapter.web.asset.series

import com.refinvest.core.asset.domain.AssetSeries

data class SeriesResponse(val symbol: String, val points: List<SeriesPointResponse>) {
    companion object {
        fun from(series: AssetSeries) = SeriesResponse(
            series.symbol,
            series.points.map { point -> SeriesPointResponse(point.date, point.value) },
        )
    }
}
