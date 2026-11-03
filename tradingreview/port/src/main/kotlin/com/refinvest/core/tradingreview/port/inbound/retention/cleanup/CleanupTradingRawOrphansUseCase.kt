package com.refinvest.core.tradingreview.port.inbound.retention.cleanup

fun interface CleanupTradingRawOrphansUseCase {
    fun cleanupOrphans(): Int
}
