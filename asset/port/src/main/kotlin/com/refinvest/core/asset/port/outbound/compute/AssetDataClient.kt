package com.refinvest.core.asset.port.outbound.compute

import com.refinvest.core.asset.domain.Asset
import com.refinvest.core.asset.domain.AssetAvailability
import com.refinvest.core.asset.domain.AssetSeries
import com.refinvest.core.asset.domain.SeriesSnapshot

interface AssetDataClient {
    fun listAssets(): List<Asset>

    fun getAvailability(symbol: String): AssetAvailability?

    fun getSeries(query: AssetSeriesQuery): SeriesSnapshot
}
