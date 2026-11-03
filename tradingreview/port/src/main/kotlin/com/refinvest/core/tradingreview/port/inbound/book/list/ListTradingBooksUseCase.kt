package com.refinvest.core.tradingreview.port.inbound.book.list

import com.refinvest.core.tradingreview.port.inbound.book.TradingBookResult
import com.refinvest.core.tradingreview.port.inbound.pagination.TradingPage

fun interface ListTradingBooksUseCase {
    fun execute(query: ListTradingBooksQuery): TradingPage<TradingBookResult>
}
