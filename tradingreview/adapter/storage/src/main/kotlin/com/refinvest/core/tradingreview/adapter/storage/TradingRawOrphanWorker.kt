package com.refinvest.core.tradingreview.adapter.storage

import com.refinvest.core.tradingreview.port.inbound.retention.cleanup.CleanupTradingRawOrphansUseCase

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "refinvest.trading-review.raw", name = ["root", "keystore-path", "keystore-password", "key-alias"])
class TradingRawOrphanWorker(
    private val cleanup: CleanupTradingRawOrphansUseCase,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 60000)
    fun cleanup() {
        try {
            cleanup.cleanupOrphans()
        } catch (_: Exception) {
            log.warn("Trading raw orphan cleanup failed")
        }
    }
}
