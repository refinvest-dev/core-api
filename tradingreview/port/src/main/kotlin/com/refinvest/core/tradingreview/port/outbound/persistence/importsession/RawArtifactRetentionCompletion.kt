package com.refinvest.core.tradingreview.port.outbound.persistence.importsession

import java.time.Instant

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
