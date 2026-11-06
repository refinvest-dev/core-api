package com.refinvest.core.tradingreview.application

import com.refinvest.core.tradingreview.port.inbound.retention.cleanup.CleanupTradingRawOrphansUseCase
import com.refinvest.core.tradingreview.port.outbound.persistence.importsession.TradingSourceArtifactReader
import com.refinvest.core.tradingreview.port.outbound.storage.raw.RawArtifactStorage

import java.time.Clock
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class TradingRawOrphanCleanupService(
    private val storage: RawArtifactStorage,
    private val artifacts: TradingSourceArtifactReader,
    private val clock: Clock,
    @Value("\${refinvest.trading-review.raw.orphan-grace-seconds:3600}")
    private val graceSeconds: Long = 3600L,
) : CleanupTradingRawOrphansUseCase {
    override fun cleanupOrphans(): Int {
        if (graceSeconds < 600) return 0
        return storage.cleanupOrphans(clock.instant().minusSeconds(graceSeconds), 20, artifacts::referencesObjectKey)
    }
}
