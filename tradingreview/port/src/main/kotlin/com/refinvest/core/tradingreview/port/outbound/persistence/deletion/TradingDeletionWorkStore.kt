package com.refinvest.core.tradingreview.port.outbound.persistence.deletion

import com.refinvest.core.tradingreview.domain.DeletionRequest
import com.refinvest.core.tradingreview.domain.valueobject.*
import java.time.Duration
import java.time.Instant

interface TradingDeletionWorkStore {
    fun claim(now: Instant, token: String, leaseDuration: Duration): DeletionWorkLease?
    fun plan(lease: DeletionWorkLease, now: Instant, limit: Int): List<DeletionWorkItem>
    /** All progress, metadata revocation and release are conditional on the same live lease/version. */
    fun finish(lease: DeletionWorkLease, completed: List<DeletionWorkItem>, now: Instant,
               retryAfter: Duration, failed: Boolean = false): Boolean
    fun remediate(failedId: DeletionRequestId, newId: DeletionRequestId, now: Instant): DeletionRequest
}
