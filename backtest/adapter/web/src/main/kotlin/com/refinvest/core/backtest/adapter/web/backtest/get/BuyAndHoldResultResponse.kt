package com.refinvest.core.backtest.adapter.web.backtest.get

import com.refinvest.core.backtest.domain.backtest.BuyAndHoldResult
import java.math.BigDecimal

data class BuyAndHoldResultResponse(
    val asset: String,
    val equityCurve: List<EquityCurvePointResponse>,
    val totalReturn: BigDecimal,
    val cagr: BigDecimal?,
    val mdd: BigDecimal,
) {
    companion object {
        fun from(result: BuyAndHoldResult): BuyAndHoldResultResponse = BuyAndHoldResultResponse(
            asset = result.asset,
            equityCurve = result.equityCurve.map(EquityCurvePointResponse::from),
            totalReturn = result.totalReturn,
            cagr = result.cagr,
            mdd = result.mdd,
        )
    }
}
