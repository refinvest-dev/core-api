package com.refinvest.core.backtest.adapter.web.backtest.run

import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal

data class FeeModelRequest(
    @field:NotNull
    @field:DecimalMin("0.0")
    val commission: BigDecimal,
    @field:NotNull
    @field:DecimalMin("0.0")
    val slippage: BigDecimal,
)
