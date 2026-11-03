package com.refinvest.core.tradingreview.port.normalization.validate

import com.refinvest.core.tradingreview.port.importsession.TradingImportSessionResult

fun interface ValidateTradingImportSessionUseCase {
    fun execute(command: ValidateTradingImportSessionCommand): TradingImportSessionResult
}
