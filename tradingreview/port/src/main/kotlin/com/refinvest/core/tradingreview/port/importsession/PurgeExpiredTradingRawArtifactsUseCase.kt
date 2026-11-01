package com.refinvest.core.tradingreview.port.importsession

data class TradingRawRetentionResult(val overdue: Long, val deleted: Int, val waiting: Int, val failures: Int)

fun interface PurgeExpiredTradingRawArtifactsUseCase {
    fun purgeExpired(): TradingRawRetentionResult
}
