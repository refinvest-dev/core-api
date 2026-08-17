package com.refinvest.core.backtest.adapter.web.backtest.run

import java.math.BigDecimal

data class FeeModelResponse(
    val commission: BigDecimal,
    val slippage: BigDecimal,
)
