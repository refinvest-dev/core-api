package com.refinvest.core.tradingreview.port.inbound.book.get

import com.refinvest.core.tradingreview.domain.valueobject.TradingBookId

data class GetTradingBookQuery(val id: TradingBookId)
