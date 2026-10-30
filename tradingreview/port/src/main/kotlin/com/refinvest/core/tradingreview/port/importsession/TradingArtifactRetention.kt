package com.refinvest.core.tradingreview.port.importsession

import com.refinvest.core.tradingreview.domain.importsession.TradingSourceArtifact
import com.refinvest.core.tradingreview.domain.valueobject.*
import java.time.Instant

/** Raw purge saga; the claimed artifact version fences late completion and failure. */
interface TradingArtifactRetention {
    fun claimDue(now: Instant, limit: Int): List<TradingSourceArtifact>
    fun markDeleted(artifact: TradingSourceArtifact, now: Instant): Boolean
    fun markDeletionFailed(artifact: TradingSourceArtifact, code: String, now: Instant)
}
