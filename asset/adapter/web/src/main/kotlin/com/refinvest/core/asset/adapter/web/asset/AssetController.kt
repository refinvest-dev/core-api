package com.refinvest.core.asset.adapter.web.asset

import com.refinvest.core.asset.adapter.web.asset.availability.AssetAvailabilityResponse
import com.refinvest.core.asset.adapter.web.asset.list.AssetListResponse
import com.refinvest.core.asset.port.inbound.availability.GetAssetAvailabilityQuery
import com.refinvest.core.asset.port.inbound.availability.GetAssetAvailabilityUseCase
import com.refinvest.core.asset.port.inbound.list.ListAssetsUseCase
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.HttpStatus

@RestController
@RequestMapping("/assets")
class AssetController(
    private val listAssetsUseCase: ListAssetsUseCase,
    private val getAssetAvailabilityUseCase: GetAssetAvailabilityUseCase,
) {
    @GetMapping
    fun list(): AssetListResponse = AssetListResponse.from(listAssetsUseCase.execute())

    @GetMapping("/{symbol}/availability")
    fun availability(@PathVariable symbol: String): AssetAvailabilityResponse =
        getAssetAvailabilityUseCase.execute(GetAssetAvailabilityQuery(symbol))
            ?.let(AssetAvailabilityResponse::from)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found")

    @GetMapping("/series")
    fun series(): Nothing =
        throw ResponseStatusException(HttpStatus.NOT_FOUND, "Asset series is unavailable in the MVP")
}
