package com.refinvest.core.tradingreview.port.importsession

fun interface ExpireTradingImportSourcesUseCase {
    fun expireDue(): Int
}
