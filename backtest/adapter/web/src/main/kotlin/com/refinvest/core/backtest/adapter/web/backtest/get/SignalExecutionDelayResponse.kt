package com.refinvest.core.backtest.adapter.web.backtest.get

import com.refinvest.core.backtest.domain.backtest.SignalExecutionDelay
import java.math.BigDecimal

data class SignalExecutionDelayResponse(
    val median: BigDecimal,
    val max: BigDecimal,
    val distribution: List<BigDecimal>,
) {
    companion object {
        fun from(delay: SignalExecutionDelay): SignalExecutionDelayResponse = SignalExecutionDelayResponse(
            delay.median,
            delay.max,
            delay.distribution,
        )
    }
}
