package com.refinvest.core.tradingreview.port.inbound.normalization.cleanup

fun interface CleanupTradingResultSpoolUseCase {
    fun cleanup(): Int
}
