package com.refinvest.core.asset.domain

import java.math.BigDecimal
import java.time.LocalDate

data class SeriesPoint(
    val date: LocalDate,
    val value: BigDecimal,
)
