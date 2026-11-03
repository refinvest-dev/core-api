package com.refinvest.core.tradingreview.port.book.get

import com.refinvest.core.tradingreview.port.book.TradingBookResult

fun interface GetTradingBookUseCase {
    fun execute(query: GetTradingBookQuery): TradingBookResult
}
