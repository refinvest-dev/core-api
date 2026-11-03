package com.refinvest.core.tradingreview.port.inbound.retention.expire

fun interface ExpireTradingImportSourcesUseCase {
    fun expireDue(): Int
}
