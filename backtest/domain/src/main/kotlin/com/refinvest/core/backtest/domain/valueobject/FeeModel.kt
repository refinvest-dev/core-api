package com.refinvest.core.backtest.domain.valueobject

data class FeeModel(
    val commission: Percent,
    val slippage: Percent,
)
