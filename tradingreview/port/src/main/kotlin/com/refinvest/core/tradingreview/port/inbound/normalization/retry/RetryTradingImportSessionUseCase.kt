package com.refinvest.core.tradingreview.port.inbound.normalization.retry

import com.refinvest.core.tradingreview.port.inbound.importsession.TradingImportSessionResult

fun interface RetryTradingImportSessionUseCase {
    fun execute(command: RetryTradingImportSessionCommand): TradingImportSessionResult
}
