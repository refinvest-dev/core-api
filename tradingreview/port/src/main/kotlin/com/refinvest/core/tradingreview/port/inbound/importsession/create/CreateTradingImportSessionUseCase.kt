package com.refinvest.core.tradingreview.port.inbound.importsession.create

import com.refinvest.core.tradingreview.port.inbound.importsession.TradingImportSessionResult

fun interface CreateTradingImportSessionUseCase {
    fun execute(command: CreateTradingImportSessionCommand): TradingImportSessionResult
}
