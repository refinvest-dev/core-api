package com.refinvest.core.backtest.domain.backtest

import java.math.BigDecimal
import java.time.LocalDate

data class EquityCurvePoint(
    val date: LocalDate,
    val value: BigDecimal,
)
