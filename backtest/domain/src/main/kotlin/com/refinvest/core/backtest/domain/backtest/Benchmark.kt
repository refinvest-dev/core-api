package com.refinvest.core.backtest.domain.backtest

data class Benchmark(
    val primary: BuyAndHoldResult,
    val secondaryReference: BuyAndHoldResult?,
)
