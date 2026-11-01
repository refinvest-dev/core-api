package com.refinvest.core.tradingreview.port.importsession

fun interface CleanupTradingRawOrphansUseCase {
    fun cleanupOrphans(): Int
}
