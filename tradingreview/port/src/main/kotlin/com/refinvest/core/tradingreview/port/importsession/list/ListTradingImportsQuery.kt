package com.refinvest.core.tradingreview.port.importsession.list

import com.refinvest.core.tradingreview.domain.valueobject.TradingBookId

data class ListTradingImportsQuery(val bookId: TradingBookId, val page: Int, val size: Int)
