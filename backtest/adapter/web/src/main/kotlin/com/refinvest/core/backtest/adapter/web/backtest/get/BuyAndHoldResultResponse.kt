package com.refinvest.core.backtest.adapter.web.backtest.get

import com.refinvest.core.backtest.domain.backtest.BuyAndHoldResult
import java.math.BigDecimal

data class BuyAndHoldResultResponse(
    val totalReturn: BigDecimal,
    val cagr: BigDecimal,
    val mdd: BigDecimal,
) {
    companion object {
        fun from(result: BuyAndHoldResult): BuyAndHoldResultResponse = BuyAndHoldResultResponse(
            result.totalReturn,
            result.cagr,
            result.mdd,
        )
    }
}
