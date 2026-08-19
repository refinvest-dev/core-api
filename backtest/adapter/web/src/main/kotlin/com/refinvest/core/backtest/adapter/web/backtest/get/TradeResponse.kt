package com.refinvest.core.backtest.adapter.web.backtest.get

import com.refinvest.core.backtest.domain.backtest.Trade
import java.math.BigDecimal
import java.time.Instant

data class TradeResponse(
    val signalTime: Instant,
    val entryTime: Instant,
    val entryPrice: BigDecimal,
    val exitTime: Instant,
    val exitPrice: BigDecimal,
    val returnPct: BigDecimal,
    val holdingPeriod: Int,
) {
    companion object {
        fun from(trade: Trade): TradeResponse = TradeResponse(
            trade.signalTime,
            trade.entryTime,
            trade.entryPrice,
            trade.exitTime,
            trade.exitPrice,
            trade.returnPct,
            trade.holdingPeriod,
        )
    }
}
