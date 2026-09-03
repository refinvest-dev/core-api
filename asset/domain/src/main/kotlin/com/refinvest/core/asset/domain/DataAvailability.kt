package com.refinvest.core.asset.domain

import java.time.LocalDate

data class DataAvailability(
    val firstDate: LocalDate,
    val lastDate: LocalDate,
)
