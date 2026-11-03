package com.refinvest.core.tradingreview.port.book.create

import com.refinvest.core.tradingreview.port.book.TradingBookResult

fun interface CreateTradingBookUseCase {
    fun execute(command: CreateTradingBookCommand): TradingBookResult
}
