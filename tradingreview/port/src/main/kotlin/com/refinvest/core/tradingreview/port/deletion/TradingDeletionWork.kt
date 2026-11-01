package com.refinvest.core.tradingreview.port.deletion

import com.refinvest.core.tradingreview.domain.DeletionRequest
import com.refinvest.core.tradingreview.domain.valueobject.*
import java.time.Duration
import java.time.Instant

data class DeletionWorkLease(val request: DeletionRequest, val token: String, val expiresAt: Instant)

/** Bounded, payload-free work prepared in a short transaction. */
sealed interface DeletionWorkItem {
    data class Runtime(val command: TradingRuntimeDeletion) : DeletionWorkItem
    data class Copies(val scope: TradingArtifactScope) : DeletionWorkItem
    data class Revision(val id: LedgerRevisionId) : DeletionWorkItem
    data class Object(val artifactId: TradingSourceArtifactId, val key: String?) : DeletionWorkItem
    data object LegacyCopies : DeletionWorkItem
}

interface TradingDeletionWorkStore {
    fun claim(now: Instant, token: String, leaseDuration: Duration): DeletionWorkLease?
    fun plan(lease: DeletionWorkLease, now: Instant, limit: Int): List<DeletionWorkItem>
    /** All progress, metadata revocation and release are conditional on the same live lease/version. */
    fun finish(lease: DeletionWorkLease, completed: List<DeletionWorkItem>, now: Instant,
               retryAfter: Duration, failed: Boolean = false): Boolean
    fun remediate(failedId: DeletionRequestId, newId: DeletionRequestId, now: Instant): DeletionRequest
}

interface TradingDeletionUseCase { fun purgePending() }
interface RemediateTradingDeletionUseCase { fun remediate(failedId: DeletionRequestId): DeletionRequest }
