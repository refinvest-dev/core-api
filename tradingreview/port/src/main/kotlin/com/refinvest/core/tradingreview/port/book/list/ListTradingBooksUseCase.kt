package com.refinvest.core.tradingreview.port.book.list

import com.refinvest.core.tradingreview.port.book.TradingBookResult
import com.refinvest.core.tradingreview.port.pagination.TradingPage

fun interface ListTradingBooksUseCase {
    fun execute(query: ListTradingBooksQuery): TradingPage<TradingBookResult>
}
