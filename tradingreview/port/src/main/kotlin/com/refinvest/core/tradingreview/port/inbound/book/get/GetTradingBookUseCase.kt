package com.refinvest.core.tradingreview.port.inbound.book.get

import com.refinvest.core.tradingreview.port.inbound.book.TradingBookResult

fun interface GetTradingBookUseCase {
    fun execute(query: GetTradingBookQuery): TradingBookResult
}
