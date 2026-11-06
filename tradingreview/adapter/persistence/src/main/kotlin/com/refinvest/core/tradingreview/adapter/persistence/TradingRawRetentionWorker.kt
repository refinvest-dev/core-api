package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.tradingreview.port.inbound.retention.purge.PurgeExpiredTradingRawArtifactsUseCase

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.atomic.AtomicLong
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "refinvest.trading-review", name = ["retention-enabled"], havingValue = "true", matchIfMissing = true)
class TradingRawRetentionWorker(
    private val retention: PurgeExpiredTradingRawArtifactsUseCase,
    registry: MeterRegistry,
) {
    private val overdue = AtomicLong()
    private val deletionFailures = registry.counter("trading.review.raw.deletion.failures")

    init { Gauge.builder("trading.review.raw.overdue", overdue) { it.get().toDouble() }.register(registry) }

    @Scheduled(fixedDelay = 5000)
    fun purgeDue() {
        val result = retention.purgeExpired()
        overdue.set(result.overdue)
        if (result.failures > 0) deletionFailures.increment(result.failures.toDouble())
    }
}
