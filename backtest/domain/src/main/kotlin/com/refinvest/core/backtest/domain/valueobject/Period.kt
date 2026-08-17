package com.refinvest.core.backtest.domain.valueobject

import java.time.LocalDate

data class Period(
    val start: LocalDate,
    val end: LocalDate,
) {
    init {
        require(!end.isBefore(start)) { "period end must not be before start" }
    }
}
