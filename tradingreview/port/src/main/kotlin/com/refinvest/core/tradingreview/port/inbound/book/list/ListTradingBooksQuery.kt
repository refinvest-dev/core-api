package com.refinvest.core.tradingreview.port.inbound.book.list

import com.refinvest.core.tradingreview.domain.valueobject.TradingAccountId

data class ListTradingBooksQuery(val accountId: TradingAccountId, val page: Int, val size: Int)
