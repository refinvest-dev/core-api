package com.refinvest.core.asset.application.series

import com.refinvest.core.asset.port.inbound.series.GetSeriesQuery
import com.refinvest.core.asset.port.inbound.series.GetSeriesResult
import com.refinvest.core.asset.port.inbound.series.GetSeriesUseCase
import com.refinvest.core.asset.port.outbound.compute.AssetDataClient
import com.refinvest.core.asset.port.outbound.compute.AssetSeriesQuery
import org.springframework.stereotype.Service

@Service
class GetSeriesService(
    private val assetDataClient: AssetDataClient,
) : GetSeriesUseCase {
    override fun execute(query: GetSeriesQuery): GetSeriesResult = GetSeriesResult(
        assetDataClient.getSeries(
            AssetSeriesQuery(query.symbols, query.metric, query.start, query.end, query.datasetSnapshotId),
        ),
    )
}
