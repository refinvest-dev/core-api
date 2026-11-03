package com.refinvest.core.tradingreview.port.inbound.book.delete

import com.refinvest.core.tradingreview.port.inbound.deletion.TradingDeletion

fun interface DeleteTradingBookUseCase {
    fun execute(command: DeleteTradingBookCommand): TradingDeletion
}
