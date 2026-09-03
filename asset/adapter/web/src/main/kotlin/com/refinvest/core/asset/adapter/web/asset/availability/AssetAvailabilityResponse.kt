package com.refinvest.core.asset.adapter.web.asset.availability

import com.refinvest.core.asset.port.inbound.availability.GetAssetAvailabilityResult
import java.time.Instant
import java.time.LocalDate

data class AssetAvailabilityResponse(
    val symbol: String,
    val inceptionDate: LocalDate,
    val dataAvailability: DataAvailabilityResponse,
    val datasetSnapshotId: String,
    val snapshotCreatedAt: Instant,
) {
    companion object {
        fun from(result: GetAssetAvailabilityResult): AssetAvailabilityResponse = AssetAvailabilityResponse(
            symbol = result.availability.symbol,
            inceptionDate = result.availability.inceptionDate,
            dataAvailability = DataAvailabilityResponse(
                result.availability.dataAvailability.firstDate,
                result.availability.dataAvailability.lastDate,
            ),
            datasetSnapshotId = result.availability.datasetSnapshotId,
            snapshotCreatedAt = result.availability.snapshotCreatedAt,
        )
    }
}
