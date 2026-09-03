package com.refinvest.core.asset.application.availability

import com.refinvest.core.asset.port.inbound.availability.GetAssetAvailabilityQuery
import com.refinvest.core.asset.port.inbound.availability.GetAssetAvailabilityResult
import com.refinvest.core.asset.port.inbound.availability.GetAssetAvailabilityUseCase
import com.refinvest.core.asset.port.outbound.compute.AssetDataClient
import org.springframework.stereotype.Service

@Service
class GetAssetAvailabilityService(
    private val assetDataClient: AssetDataClient,
) : GetAssetAvailabilityUseCase {
    override fun execute(query: GetAssetAvailabilityQuery): GetAssetAvailabilityResult? =
        assetDataClient.getAvailability(query.symbol)?.let(::GetAssetAvailabilityResult)
}
