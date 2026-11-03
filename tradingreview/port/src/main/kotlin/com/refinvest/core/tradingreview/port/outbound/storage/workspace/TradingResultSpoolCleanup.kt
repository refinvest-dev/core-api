package com.refinvest.core.tradingreview.port.outbound.storage.workspace

import java.time.Instant

fun interface TradingResultSpoolCleanup {
    fun cleanupOlderThan(cutoff: Instant): Int
}
