package com.refinvest.core.tradingreview.port.inbound.book.create

import com.refinvest.core.tradingreview.port.inbound.book.TradingBookResult

fun interface CreateTradingBookUseCase {
    fun execute(command: CreateTradingBookCommand): TradingBookResult
}
