package com.refinvest.core.tradingreview.port.inbound.normalization.validate

import com.refinvest.core.tradingreview.port.inbound.importsession.TradingImportSessionResult

fun interface ValidateTradingImportSessionUseCase {
    fun execute(command: ValidateTradingImportSessionCommand): TradingImportSessionResult
}
