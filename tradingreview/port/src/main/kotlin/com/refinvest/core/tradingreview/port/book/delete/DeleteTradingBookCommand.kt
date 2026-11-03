package com.refinvest.core.tradingreview.port.book.delete

import com.refinvest.core.tradingreview.domain.valueobject.TradingBookId

data class DeleteTradingBookCommand(val id: TradingBookId, val key: String)
