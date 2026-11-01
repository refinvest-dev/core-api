package com.refinvest.core.tradingreview.port.normalization

/** Removes a bounded batch of obsolete stages and ACKs for deleted sessions. */
fun interface CleanupTradingNormalizationStagingUseCase {
    fun cleanupObsoleteStaging(): Int
}
