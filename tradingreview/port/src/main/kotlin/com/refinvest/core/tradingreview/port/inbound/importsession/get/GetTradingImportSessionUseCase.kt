package com.refinvest.core.tradingreview.port.inbound.importsession.get

import com.refinvest.core.tradingreview.port.inbound.importsession.TradingImportSessionView

fun interface GetTradingImportSessionUseCase {
    fun execute(query: GetTradingImportSessionQuery): TradingImportSessionView
}
