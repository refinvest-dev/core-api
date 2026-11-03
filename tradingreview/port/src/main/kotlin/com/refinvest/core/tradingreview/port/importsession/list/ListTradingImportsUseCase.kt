package com.refinvest.core.tradingreview.port.importsession.list

import com.refinvest.core.tradingreview.port.importsession.TradingImportSessionView
import com.refinvest.core.tradingreview.port.pagination.TradingPage

fun interface ListTradingImportsUseCase {
    fun execute(query: ListTradingImportsQuery): TradingPage<TradingImportSessionView>
}
