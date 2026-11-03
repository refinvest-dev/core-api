package com.refinvest.core.tradingreview.port.outbound.persistence.importsession

import com.refinvest.core.tradingreview.domain.importsession.TradingSourceArtifact
import com.refinvest.core.tradingreview.domain.valueobject.TradingArtifactRetentionStatus
import java.time.Instant

data class RawArtifactRetentionClaim(
    val artifact: TradingSourceArtifact,
    val leaseUntil: Instant,
    val failureCount: Int,
) {
    init {
        require(artifact.retentionStatus == TradingArtifactRetentionStatus.DELETION_PENDING)
        require(failureCount >= 0)
    }
}
