package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.tradingreview.port.RawArtifactStorage
import com.refinvest.core.tradingreview.port.TradingSourceArtifactStore
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.concurrent.atomic.AtomicLong

@Component
@ConditionalOnProperty(prefix = "refinvest.trading-review", name = ["retention-enabled"], havingValue = "true", matchIfMissing = true)
class TradingRawRetentionWorker(
    private val artifacts: TradingSourceArtifactStore,
    private val storage: RawArtifactStorage,
    private val clock: Clock,
    private val jdbc: JdbcTemplate,
    registry: MeterRegistry,
) {
    private val overdue = AtomicLong()
    private val deletionFailures = registry.counter("trading.review.raw.deletion.failures")

    init { Gauge.builder("trading.review.raw.overdue", overdue) { it.get().toDouble() }.register(registry) }

    @Scheduled(fixedDelay = 5000)
    fun purgeDue() {
        overdue.set(jdbc.queryForObject("""SELECT count(*) FROM trading_source_artifacts
            WHERE retention_status IN ('ACTIVE','RETENTION_SCHEDULED') AND raw_expires_at <= CURRENT_TIMESTAMP""",
            Long::class.java) ?: 0L)
        for (artifact in artifacts.claimDue(clock.instant(), 20)) {
            try {
                val key = artifact.objectKey ?: throw IllegalStateException("Missing raw object descriptor")
                storage.delete(key)
                if (storage.exists(key)) throw IllegalStateException("Raw object still exists")
                if (!artifacts.markDeleted(artifact.id, clock.instant()))
                    throw IllegalStateException("Raw retention state changed")
            } catch (_: Exception) {
                deletionFailures.increment()
                artifacts.markDeletionFailed(artifact.id, "ARTIFACT_DELETION_FAILED", clock.instant())
            }
        }
    }
}
