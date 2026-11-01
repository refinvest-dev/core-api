package com.refinvest.core.tradingreview.port.importsession

import java.time.Instant

enum class RawArtifactPurgeOutcome { ABSENT, WAITING, RETRY, EXHAUSTED }

/** WAITING drains active copies without spending the dependency failure budget. */
data class RawArtifactRetentionCompletion(
    val claim: RawArtifactRetentionClaim,
    val outcome: RawArtifactPurgeOutcome,
    val observedAt: Instant,
    val nextAttemptAt: Instant? = null,
) {
    init {
        require((nextAttemptAt != null) == (outcome in setOf(RawArtifactPurgeOutcome.WAITING, RawArtifactPurgeOutcome.RETRY)))
        require(nextAttemptAt == null || nextAttemptAt.isAfter(observedAt))
    }
}
