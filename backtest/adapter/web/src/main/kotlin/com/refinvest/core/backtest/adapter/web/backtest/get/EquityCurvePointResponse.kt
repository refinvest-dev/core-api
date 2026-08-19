package com.refinvest.core.backtest.adapter.web.backtest.get

import com.refinvest.core.backtest.domain.backtest.EquityCurvePoint
import java.math.BigDecimal
import java.time.LocalDate

data class EquityCurvePointResponse(
    val date: LocalDate,
    val value: BigDecimal,
) {
    companion object {
        fun from(point: EquityCurvePoint): EquityCurvePointResponse = EquityCurvePointResponse(point.date, point.value)
    }
}
