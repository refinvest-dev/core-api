package com.refinvest.core.tradingreview.port.outbound.persistence.importsession

import java.time.Duration
import java.time.Instant

interface TradingArtifactRetention {
    fun countOverdue(now: Instant): Long
    fun claimDue(now: Instant, limit: Int, lease: Duration = Duration.ofMinutes(5)): List<RawArtifactRetentionClaim>
    /** Locks the current row before checking version and lease expiry; stale progress changes nothing. */
    fun finish(completion: RawArtifactRetentionCompletion): Boolean
}
