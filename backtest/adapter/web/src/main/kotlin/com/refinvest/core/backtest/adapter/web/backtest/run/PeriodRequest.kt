package com.refinvest.core.backtest.adapter.web.backtest.run

import jakarta.validation.constraints.NotNull
import java.time.LocalDate

data class PeriodRequest(
    @field:NotNull
    val start: LocalDate,
    @field:NotNull
    val end: LocalDate,
)
