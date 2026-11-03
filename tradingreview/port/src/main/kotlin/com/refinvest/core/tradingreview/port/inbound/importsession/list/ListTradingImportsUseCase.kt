package com.refinvest.core.tradingreview.port.inbound.importsession.list

import com.refinvest.core.tradingreview.port.inbound.importsession.TradingImportSessionView
import com.refinvest.core.tradingreview.port.inbound.pagination.TradingPage

fun interface ListTradingImportsUseCase {
    fun execute(query: ListTradingImportsQuery): TradingPage<TradingImportSessionView>
}
