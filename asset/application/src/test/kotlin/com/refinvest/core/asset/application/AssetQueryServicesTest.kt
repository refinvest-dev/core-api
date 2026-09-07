package com.refinvest.core.asset.application

import com.refinvest.core.asset.application.availability.GetAssetAvailabilityService
import com.refinvest.core.asset.application.list.ListAssetsService
import com.refinvest.core.asset.application.series.GetSeriesService
import com.refinvest.core.asset.domain.Asset
import com.refinvest.core.asset.domain.AssetAvailability
import com.refinvest.core.asset.domain.AssetCalendar
import com.refinvest.core.asset.domain.AssetSeries
import com.refinvest.core.asset.domain.DataAvailability
import com.refinvest.core.asset.domain.SeriesPoint
import com.refinvest.core.asset.domain.SeriesSnapshot
import com.refinvest.core.asset.port.inbound.availability.GetAssetAvailabilityQuery
import com.refinvest.core.asset.port.inbound.series.GetSeriesQuery
import com.refinvest.core.asset.port.outbound.compute.AssetDataClient
import com.refinvest.core.asset.port.outbound.compute.AssetSeriesQuery
import com.refinvest.core.asset.port.outbound.compute.SeriesMetric
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AssetQueryServicesTest {
    private val asset = Asset(
        "QQQ", AssetCalendar.US_EQUITY, LocalDate.of(1999, 3, 10), true,
        DataAvailability(LocalDate.of(2020, 1, 1), LocalDate.of(2026, 1, 1)), emptyList(),
    )

    @Test
    fun `lists assets from the Compute boundary`() {
        val vix = asset.copy(symbol = "VIX")
        val result = ListAssetsService(FakeAssetDataClient(assets = listOf(asset, vix))).execute()

        assertEquals(listOf(asset), result.assets)
    }

    @Test
    fun `returns null when Compute has no matching asset availability`() {
        val result = GetAssetAvailabilityService(FakeAssetDataClient()).execute(GetAssetAvailabilityQuery("UNKNOWN"))

        assertNull(result)
    }

    @Test
    fun `forwards the requested series query to the Compute boundary`() {
        val client = FakeAssetDataClient(
            seriesSnapshot = SeriesSnapshot(
                "snapshot-1", Instant.parse("2026-02-01T00:00:00Z"), "ADJUSTED",
                listOf(AssetSeries("QQQ", listOf(SeriesPoint(LocalDate.of(2026, 1, 2), BigDecimal("100"))))),
            ),
        )
        val query = GetSeriesQuery(
            listOf("QQQ"), SeriesMetric.NORMALIZED, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), "snapshot-1",
        )

        val result = GetSeriesService(client).execute(query)

        assertEquals(AssetSeriesQuery(query.symbols, query.metric, query.start, query.end, query.datasetSnapshotId), client.lastSeriesQuery)
        assertEquals("snapshot-1", result.snapshot.datasetSnapshotId)
        assertEquals("QQQ", result.snapshot.series.single().symbol)
    }

    private class FakeAssetDataClient(
        private val assets: List<Asset> = emptyList(),
        private val availability: AssetAvailability? = null,
        private val seriesSnapshot: SeriesSnapshot = SeriesSnapshot("snapshot", Instant.EPOCH, "ADJUSTED", emptyList()),
    ) : AssetDataClient {
        var lastSeriesQuery: AssetSeriesQuery? = null

        override fun listAssets(): List<Asset> = assets

        override fun getAvailability(symbol: String): AssetAvailability? = availability

        override fun getSeries(query: AssetSeriesQuery): SeriesSnapshot {
            lastSeriesQuery = query
            return seriesSnapshot
        }
    }
}
