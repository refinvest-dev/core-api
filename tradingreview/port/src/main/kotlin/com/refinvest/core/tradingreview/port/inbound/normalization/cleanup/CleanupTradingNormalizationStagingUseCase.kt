package com.refinvest.core.tradingreview.port.inbound.normalization.cleanup

fun interface CleanupTradingNormalizationStagingUseCase {
    fun cleanupObsoleteStaging(): Int
}
