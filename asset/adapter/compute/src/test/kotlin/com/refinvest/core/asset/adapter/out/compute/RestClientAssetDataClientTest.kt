package com.refinvest.core.asset.adapter.out.compute

import com.refinvest.core.asset.domain.AssetCalendar
import com.refinvest.core.asset.domain.exception.DatasetSnapshotNotFoundException
import com.refinvest.core.asset.domain.exception.SeriesDataUnavailableException
import com.refinvest.core.asset.port.outbound.compute.AssetSeriesQuery
import com.refinvest.core.asset.port.outbound.compute.SeriesMetric
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.web.client.RestClient
import org.springframework.http.HttpStatus
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith

class RestClientAssetDataClientTest {
    @Test
    fun `maps asset metadata without exposing the Compute DTO`() {
        val fixture = client()
        fixture.server.expect(requestTo("http://compute/assets"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("X-Internal-Api-Key", "test-key"))
            .andRespond(withSuccess("""
                {"assets":[{"symbol":"QQQ","calendar":"US_EQUITY","inceptionDate":"1999-03-10","executionEnabled":true,
                "dataAvailability":{"firstDate":"2020-01-01","lastDate":"2026-01-01"},
                "corporateActions":[{"type":"SPLIT","effectiveDate":"2022-01-01","ratio":2.0}]}]}
            """.trimIndent(), MediaType.APPLICATION_JSON))

        val asset = fixture.client.listAssets().single()

        assertEquals(AssetCalendar.US_EQUITY, asset.calendar)
        assertEquals(BigDecimal("2.0"), asset.corporateActions.single().ratio)
        fixture.server.verify()
    }

    @Test
    fun `maps series and passes the requested query to Compute`() {
        val fixture = client()
        fixture.server.expect(requestTo("http://compute/series?symbols=QQQ,BTCUSDT&metric=NORMALIZED&start=2026-01-01&end=2026-01-31&datasetSnapshotId=snapshot-1"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("X-Internal-Api-Key", "test-key"))
            .andRespond(withSuccess("""
                {"datasetSnapshotId":"snapshot-1","snapshotCreatedAt":"2026-02-01T00:00:00Z","adjustmentPolicy":"ADJUSTED",
                "series":[{"symbol":"QQQ","points":[{"date":"2026-01-02","value":100.0}]}]}
            """.trimIndent(), MediaType.APPLICATION_JSON))

        val series = fixture.client.getSeries(
            AssetSeriesQuery(listOf("QQQ", "BTCUSDT"), SeriesMetric.NORMALIZED, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), "snapshot-1"),
        )

        assertEquals("snapshot-1", series.datasetSnapshotId)
        assertEquals("QQQ", series.series.single().symbol)
        assertEquals(BigDecimal("100.0"), series.series.single().points.single().value)
        fixture.server.verify()
    }

    @Test
    fun `returns no availability when Compute does not know the asset`() {
        val fixture = client()
        fixture.server.expect(requestTo("http://compute/assets/UNKNOWN/availability"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withStatus(HttpStatus.NOT_FOUND))

        val availability = fixture.client.getAvailability("UNKNOWN")

        assertNull(availability)
        fixture.server.verify()
    }

    @Test
    fun `maps an invalid series request to an application-level validation error`() {
        val fixture = client()
        fixture.server.expect(requestTo("http://compute/series?symbols=UNKNOWN&metric=PRICE&start=2026-01-01&end=2026-01-31"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("Unknown asset"))

        assertFailsWith<IllegalArgumentException> {
            fixture.client.getSeries(
                AssetSeriesQuery(listOf("UNKNOWN"), SeriesMetric.PRICE, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), null),
            )
        }
        fixture.server.verify()
    }

    @Test
    fun `maps missing and unavailable snapshots to asset-level failures`() {
        val missingSnapshot = client()
        missingSnapshot.server.expect(requestTo("http://compute/series?symbols=QQQ&metric=PRICE&start=2026-01-01&end=2026-01-31&datasetSnapshotId=missing"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND))

        assertFailsWith<DatasetSnapshotNotFoundException> {
            missingSnapshot.client.getSeries(
                AssetSeriesQuery(listOf("QQQ"), SeriesMetric.PRICE, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), "missing"),
            )
        }
        missingSnapshot.server.verify()

        val unavailableSnapshot = client()
        unavailableSnapshot.server.expect(requestTo("http://compute/series?symbols=QQQ&metric=PRICE&start=2026-01-01&end=2026-01-31"))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE))

        assertFailsWith<SeriesDataUnavailableException> {
            unavailableSnapshot.client.getSeries(
                AssetSeriesQuery(listOf("QQQ"), SeriesMetric.PRICE, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), null),
            )
        }
        unavailableSnapshot.server.verify()
    }

    private fun client(): ClientFixture {
        val restClientBuilder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(restClientBuilder).build()
        return ClientFixture(
            RestClientAssetDataClient(
                "http://compute",
                "test-key",
                JsonMapper.builder().addModule(KotlinModule.Builder().build()).build(),
                restClientBuilder,
            ),
            server,
        )
    }

    private data class ClientFixture(
        val client: RestClientAssetDataClient,
        val server: MockRestServiceServer,
    )
}
