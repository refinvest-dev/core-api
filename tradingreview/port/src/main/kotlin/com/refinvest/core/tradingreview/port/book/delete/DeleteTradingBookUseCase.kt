package com.refinvest.core.tradingreview.port.book.delete

import com.refinvest.core.tradingreview.port.deletion.TradingDeletion

fun interface DeleteTradingBookUseCase {
    fun execute(command: DeleteTradingBookCommand): TradingDeletion
}
