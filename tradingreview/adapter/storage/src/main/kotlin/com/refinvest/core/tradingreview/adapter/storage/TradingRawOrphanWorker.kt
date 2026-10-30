package com.refinvest.core.tradingreview.adapter.storage

import com.refinvest.core.tradingreview.port.RawArtifactStorage
import com.refinvest.core.tradingreview.port.importsession.TradingSourceArtifactReader
import java.time.Clock
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.env.Environment
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "refinvest.trading-review.raw", name = ["root", "keystore-path", "keystore-password", "key-alias"])
class TradingRawOrphanWorker(
    private val storage: RawArtifactStorage,
    private val artifacts: TradingSourceArtifactReader,
    private val environment: Environment,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 60000)
    fun cleanup() {
        val graceSeconds = environment.getProperty("refinvest.trading-review.raw.orphan-grace-seconds", Long::class.java, 3600L)
        if (graceSeconds < 600) return
        try {
            storage.cleanupOrphans(clock.instant().minusSeconds(graceSeconds), 20, artifacts::referencesObjectKey)
        } catch (_: Exception) {
            log.warn("Trading raw orphan cleanup failed")
        }
    }
}
