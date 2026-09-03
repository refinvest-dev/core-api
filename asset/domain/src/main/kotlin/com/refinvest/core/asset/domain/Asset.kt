package com.refinvest.core.asset.domain

import java.time.LocalDate

data class Asset(
    val symbol: String,
    val calendar: AssetCalendar,
    val inceptionDate: LocalDate,
    val executionEnabled: Boolean,
    val dataAvailability: DataAvailability?,
    val corporateActions: List<CorporateAction>,
)
