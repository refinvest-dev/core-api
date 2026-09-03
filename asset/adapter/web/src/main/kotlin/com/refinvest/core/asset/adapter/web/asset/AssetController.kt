package com.refinvest.core.asset.adapter.web.asset

import com.refinvest.core.asset.adapter.web.asset.availability.AssetAvailabilityResponse
import com.refinvest.core.asset.adapter.web.asset.list.AssetListResponse
import com.refinvest.core.asset.adapter.web.asset.series.AssetSeriesResponse
import com.refinvest.core.asset.port.inbound.availability.GetAssetAvailabilityQuery
import com.refinvest.core.asset.port.inbound.availability.GetAssetAvailabilityUseCase
import com.refinvest.core.asset.port.inbound.list.ListAssetsUseCase
import com.refinvest.core.asset.port.inbound.series.GetSeriesQuery
import com.refinvest.core.asset.port.inbound.series.GetSeriesUseCase
import com.refinvest.core.asset.port.outbound.compute.SeriesMetric
import com.refinvest.core.asset.domain.exception.DatasetSnapshotNotFoundException
import com.refinvest.core.asset.domain.exception.SeriesDataUnavailableException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.HttpStatus
import java.time.LocalDate

@RestController
@RequestMapping("/assets")
class AssetController(
    private val listAssetsUseCase: ListAssetsUseCase,
    private val getAssetAvailabilityUseCase: GetAssetAvailabilityUseCase,
    private val getSeriesUseCase: GetSeriesUseCase,
) {
    @GetMapping
    fun list(): AssetListResponse = AssetListResponse.from(listAssetsUseCase.execute())

    @GetMapping("/{symbol}/availability")
    fun availability(@PathVariable symbol: String): AssetAvailabilityResponse =
        getAssetAvailabilityUseCase.execute(GetAssetAvailabilityQuery(symbol))
            ?.let(AssetAvailabilityResponse::from)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found")

    @GetMapping("/series")
    fun series(
        @RequestParam symbols: String,
        @RequestParam metric: SeriesMetric,
        @RequestParam start: LocalDate,
        @RequestParam end: LocalDate,
        @RequestParam(required = false) datasetSnapshotId: String?,
    ): AssetSeriesResponse = try {
        require(symbols.isNotBlank()) { "symbols must not be blank" }
        AssetSeriesResponse.from(
            getSeriesUseCase.execute(
                GetSeriesQuery(symbols.split(',').map(String::trim), metric, start, end, datasetSnapshotId),
            ),
        )
    } catch (exception: IllegalArgumentException) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, exception.message, exception)
    } catch (exception: DatasetSnapshotNotFoundException) {
        throw ResponseStatusException(HttpStatus.NOT_FOUND, exception.message, exception)
    } catch (exception: SeriesDataUnavailableException) {
        throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.message, exception)
    }
}
