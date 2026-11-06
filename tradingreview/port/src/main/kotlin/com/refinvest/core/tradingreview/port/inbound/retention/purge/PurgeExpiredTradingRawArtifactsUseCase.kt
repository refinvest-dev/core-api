package com.refinvest.core.tradingreview.port.inbound.retention.purge

fun interface PurgeExpiredTradingRawArtifactsUseCase {
    fun purgeExpired(): TradingRawRetentionResult
}
