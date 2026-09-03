package com.refinvest.core.asset.port.inbound.availability

fun interface GetAssetAvailabilityUseCase {
    fun execute(query: GetAssetAvailabilityQuery): GetAssetAvailabilityResult?
}
