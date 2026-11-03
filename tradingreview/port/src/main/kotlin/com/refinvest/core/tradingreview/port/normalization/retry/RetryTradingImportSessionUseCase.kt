package com.refinvest.core.tradingreview.port.normalization.retry

import com.refinvest.core.tradingreview.port.importsession.TradingImportSessionResult

fun interface RetryTradingImportSessionUseCase {
    fun execute(command: RetryTradingImportSessionCommand): TradingImportSessionResult
}
