package com.refinvest.core.backtest.domain.backtest

import java.math.BigDecimal

class SignalExecutionDelay(
    val median: BigDecimal,
    val max: BigDecimal,
    distribution: List<BigDecimal>,
) {
    val distribution: List<BigDecimal> = distribution.toList()
}
