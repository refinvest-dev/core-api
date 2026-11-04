package com.refinvest.core.tradingreview.adapter.persistence

import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import java.time.Instant
import java.time.temporal.ChronoUnit

internal fun Instant.atTradingStoredPrecision(): Instant = plusNanos(500).truncatedTo(ChronoUnit.MICROS)

internal fun tradingLifecycleMatches(currentStatus: String, currentGeneration: Long, status: String, generation: Long): Boolean =
    currentStatus in setOf("ACTIVE", "ARCHIVED") &&
        ((currentStatus == status && currentGeneration == generation) ||
            (status == "DELETION_PENDING" && Math.addExact(currentGeneration, 1) == generation))

/** Flush before JDBC capabilities observe the row, including a save whose values did not change. */
internal fun EntityManager.flushTradingSnapshot(entity: Any, expectedVersion: Long, version: () -> Long) {
    val nextVersion = Math.addExact(expectedVersion, 1)
    flush()
    if (version() == expectedVersion) lock(entity, LockModeType.PESSIMISTIC_FORCE_INCREMENT)
    check(version() == nextVersion) { "Aggregate save must advance the version exactly once" }
}
