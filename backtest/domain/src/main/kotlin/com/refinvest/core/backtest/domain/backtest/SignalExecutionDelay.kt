package com.refinvest.core.backtest.domain.backtest

import java.math.BigDecimal
import java.util.Collections

class SignalExecutionDelay(
    val median: BigDecimal,
    val max: BigDecimal,
    distribution: List<BigDecimal>,
) {
    val distribution: List<BigDecimal> = Collections.unmodifiableList(ArrayList(distribution))
}
