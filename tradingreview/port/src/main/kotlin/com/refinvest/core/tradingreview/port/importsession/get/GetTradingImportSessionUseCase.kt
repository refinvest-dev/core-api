package com.refinvest.core.tradingreview.port.importsession.get

import com.refinvest.core.tradingreview.port.importsession.TradingImportSessionView

fun interface GetTradingImportSessionUseCase {
    fun execute(query: GetTradingImportSessionQuery): TradingImportSessionView
}
