package com.refinvest.core.asset.adapter.web.asset.list

import com.refinvest.core.asset.domain.Asset
import java.time.LocalDate

data class AssetResponse(
    val symbol: String,
    val calendar: String,
    val inceptionDate: LocalDate,
    val executionEnabled: Boolean,
    val dataAvailability: DataAvailabilityResponse?,
    val corporateActions: List<CorporateActionResponse>,
) {
    companion object {
        fun from(asset: Asset) = AssetResponse(
            symbol = asset.symbol,
            calendar = asset.calendar.name,
            inceptionDate = asset.inceptionDate,
            executionEnabled = asset.executionEnabled,
            dataAvailability = asset.dataAvailability?.let(DataAvailabilityResponse::from),
            corporateActions = asset.corporateActions.map(CorporateActionResponse::from),
        )
    }
}
