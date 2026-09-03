package com.refinvest.core.asset.adapter.out.compute

import com.refinvest.core.asset.domain.Asset
import com.refinvest.core.asset.domain.AssetAvailability
import com.refinvest.core.asset.domain.AssetCalendar
import com.refinvest.core.asset.domain.AssetSeries
import com.refinvest.core.asset.domain.CorporateAction
import com.refinvest.core.asset.domain.CorporateActionType
import com.refinvest.core.asset.domain.DataAvailability
import com.refinvest.core.asset.domain.SeriesPoint
import com.refinvest.core.asset.domain.SeriesSnapshot
import com.refinvest.core.asset.domain.exception.DatasetSnapshotNotFoundException
import com.refinvest.core.asset.port.outbound.compute.AssetDataClient
import com.refinvest.core.asset.port.outbound.compute.AssetSeriesQuery
import com.refinvest.core.asset.port.outbound.compute.SeriesDataErrorCode
import com.refinvest.core.asset.port.outbound.compute.SeriesDataErrorException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.net.http.HttpClient
import java.time.Instant
import java.time.LocalDate

@Component
class RestClientAssetDataClient private constructor(
    private val restClient: RestClient,
    private val apiKey: String,
    private val objectMapper: ObjectMapper,
) : AssetDataClient {
    @Autowired
    constructor(
        @Value("\${refinvest.compute.base-url:http://localhost:8000}") baseUrl: String,
        @Value("\${refinvest.compute.api-key:}") apiKey: String,
        objectMapper: ObjectMapper,
    ) : this(createRestClient(baseUrl), apiKey, objectMapper)

    internal constructor(
        baseUrl: String,
        apiKey: String,
        objectMapper: ObjectMapper,
        restClientBuilder: RestClient.Builder,
    ) : this(restClientBuilder.baseUrl(baseUrl).build(), apiKey, objectMapper)

    override fun listAssets(): List<Asset> = restClient.get()
        .uri("/assets")
        .header(INTERNAL_API_KEY_HEADER, apiKey)
        .retrieve()
        .body(String::class.java)
        .let(::requireBody)
        .let { body -> objectMapper.readValue(body, ComputeAssetListResponse::class.java) }
        .assets
        .map { asset -> asset.toDomain() }

    override fun getAvailability(symbol: String): AssetAvailability? = restClient.get()
        .uri("/assets/{symbol}/availability", symbol)
        .header(INTERNAL_API_KEY_HEADER, apiKey)
        .exchange { _, response ->
            when (response.statusCode.value()) {
                200 -> requireBody(response.bodyTo(String::class.java))
                    .let { body -> objectMapper.readValue(body, ComputeAssetAvailabilityResponse::class.java) }
                    .toDomain()
                404 -> null
                else -> throw IllegalStateException(
                    "Compute asset availability lookup failed with HTTP ${response.statusCode.value()}",
                )
            }
        }

    override fun getSeries(query: AssetSeriesQuery): SeriesSnapshot = restClient.get()
        .uri { builder ->
            builder.path("/series")
                .queryParam("symbols", query.symbols.joinToString(","))
                .queryParam("metric", query.metric.name)
                .queryParam("start", query.start)
                .queryParam("end", query.end)
                .apply {
                    query.datasetSnapshotId?.let { snapshotId -> queryParam("datasetSnapshotId", snapshotId) }
                }
                .build()
        }
        .header(INTERNAL_API_KEY_HEADER, apiKey)
        .exchange { _, response ->
            when (response.statusCode.value()) {
                200 -> requireBody(response.bodyTo(String::class.java))
                    .let { body -> objectMapper.readValue(body, ComputeSeriesResponse::class.java) }
                    .toDomain()
                400 -> throw IllegalArgumentException(
                    response.bodyTo(String::class.java)?.takeIf(String::isNotBlank)
                        ?: "Invalid series request",
                )
                404 -> throw DatasetSnapshotNotFoundException()
                503 -> throw seriesDataUnavailable(response.bodyTo(String::class.java))
                else -> throw IllegalStateException(
                    "Compute series lookup failed with HTTP ${response.statusCode.value()}",
                )
            }
        }

    private fun requireBody(body: String?): String = requireNotNull(body) { "Compute returned an empty response body" }

    private fun seriesDataUnavailable(body: String?): SeriesDataErrorException {
        val error = try {
            objectMapper.readValue(requireBody(body), ComputeSeriesDataErrorResponse::class.java)
        } catch (exception: Exception) {
            throw IllegalStateException("Compute returned an invalid series data error response", exception)
        }
        val errorCode = SeriesDataErrorCode.entries.firstOrNull { it.name == error.errorCode }
            ?: throw IllegalStateException("Compute returned an unsupported series data error code: ${error.errorCode}")
        return SeriesDataErrorException(errorCode, error.message)
    }

    private fun ComputeAssetResponse.toDomain(): Asset = Asset(
        symbol = symbol,
        calendar = AssetCalendar.valueOf(calendar),
        inceptionDate = inceptionDate,
        executionEnabled = executionEnabled,
        dataAvailability = dataAvailability?.toDomain(),
        corporateActions = corporateActions.orEmpty().map { action -> action.toDomain() },
    )

    private fun ComputeAssetAvailabilityResponse.toDomain(): AssetAvailability = AssetAvailability(
        symbol = symbol,
        inceptionDate = inceptionDate,
        dataAvailability = dataAvailability.toDomain(),
        datasetSnapshotId = datasetSnapshotId,
        snapshotCreatedAt = snapshotCreatedAt,
    )

    private fun ComputeDataAvailabilityResponse.toDomain() = DataAvailability(firstDate, lastDate)

    private fun ComputeCorporateActionResponse.toDomain() = CorporateAction(
        type = CorporateActionType.valueOf(type),
        effectiveDate = effectiveDate,
        ratio = ratio,
        amount = amount,
    )

    private fun ComputeAssetSeriesResponse.toDomain() = AssetSeries(
        symbol = symbol,
        points = points.map { point -> SeriesPoint(point.date, point.value) },
    )

    private fun ComputeSeriesResponse.toDomain() = SeriesSnapshot(
        datasetSnapshotId = datasetSnapshotId,
        snapshotCreatedAt = snapshotCreatedAt,
        adjustmentPolicy = adjustmentPolicy,
        series = series.map { item -> item.toDomain() },
    )

    private data class ComputeAssetListResponse(val assets: List<ComputeAssetResponse>)

    private data class ComputeAssetResponse(
        val symbol: String,
        val calendar: String,
        val inceptionDate: LocalDate,
        val executionEnabled: Boolean,
        val dataAvailability: ComputeDataAvailabilityResponse? = null,
        val corporateActions: List<ComputeCorporateActionResponse>? = null,
    )

    private data class ComputeAssetAvailabilityResponse(
        val symbol: String,
        val inceptionDate: LocalDate,
        val dataAvailability: ComputeDataAvailabilityResponse,
        val datasetSnapshotId: String,
        val snapshotCreatedAt: Instant,
    )

    private data class ComputeDataAvailabilityResponse(val firstDate: LocalDate, val lastDate: LocalDate)

    private data class ComputeCorporateActionResponse(
        val type: String,
        val effectiveDate: LocalDate,
        val ratio: BigDecimal? = null,
        val amount: BigDecimal? = null,
    )

    private data class ComputeSeriesResponse(
        val datasetSnapshotId: String,
        val snapshotCreatedAt: Instant,
        val adjustmentPolicy: String,
        val series: List<ComputeAssetSeriesResponse>,
    )

    private data class ComputeSeriesDataErrorResponse(
        val message: String,
        val errorCode: String,
    )

    private data class ComputeAssetSeriesResponse(val symbol: String, val points: List<ComputeSeriesPointResponse>)

    private data class ComputeSeriesPointResponse(val date: LocalDate, val value: BigDecimal)

    private companion object {
        const val INTERNAL_API_KEY_HEADER = "X-Internal-Api-Key"

        fun createRestClient(baseUrl: String): RestClient = RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(JdkClientHttpRequestFactory(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()))
            .build()
    }
}
