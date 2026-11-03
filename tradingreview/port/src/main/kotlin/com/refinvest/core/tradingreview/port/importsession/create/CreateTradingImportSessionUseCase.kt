package com.refinvest.core.tradingreview.port.importsession.create

import com.refinvest.core.tradingreview.port.importsession.TradingImportSessionResult

fun interface CreateTradingImportSessionUseCase {
    fun execute(command: CreateTradingImportSessionCommand): TradingImportSessionResult
}
