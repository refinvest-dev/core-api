package com.refinvest.core.tradingreview.port.importsession

import java.time.Duration
import java.time.Instant

/** Raw purge saga; the claimed artifact version fences late completion and failure. */
interface TradingArtifactRetention {
    fun countOverdue(now: Instant): Long
    fun claimDue(now: Instant, limit: Int, lease: Duration = Duration.ofMinutes(5)): List<RawArtifactRetentionClaim>
    /** Locks the current row before checking version and lease expiry; stale progress changes nothing. */
    fun finish(completion: RawArtifactRetentionCompletion): Boolean
}
