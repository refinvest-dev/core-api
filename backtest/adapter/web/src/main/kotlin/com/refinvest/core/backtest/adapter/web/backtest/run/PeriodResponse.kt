package com.refinvest.core.backtest.adapter.web.backtest.run

import java.time.LocalDate

data class PeriodResponse(
    val start: LocalDate,
    val end: LocalDate,
)
