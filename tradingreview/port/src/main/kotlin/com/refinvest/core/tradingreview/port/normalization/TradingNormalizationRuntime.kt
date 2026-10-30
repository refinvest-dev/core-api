package com.refinvest.core.tradingreview.port.normalization

import com.refinvest.core.tradingreview.domain.importsession.*
import com.refinvest.core.tradingreview.domain.valueobject.*
import java.time.Duration
import java.time.Instant

interface TradingNormalizationRuntime : TradingNormalizationRunReader {
    fun holdUnknownCreate(dispatchId: String, leaseOwner: String, now: Instant): Boolean
    fun claimNext(now: Instant, lease: Duration, workerId: String): NormalizationDispatch?
    fun claimSubmitted(now: Instant, lease: Duration, workerId: String): NormalizationDispatch?
    fun schedulePoll(dispatchId: String, leaseOwner: String, next: Instant, now: Instant): Boolean
    fun schedulePollFailure(dispatchId: String, leaseOwner: String, code: String,
                            next: Instant, now: Instant): Boolean
    fun markResultReady(dispatchId: String, leaseOwner: String, now: Instant): Boolean
    fun advanceAttempt(change: NormalizationGrantAttemptAdvance): Boolean
    fun restartAfterJobFailure(change: NormalizationJobRetry): Boolean
    fun markSubmitted(change: NormalizationSubmissionReceipt): Boolean
    fun scheduleRetry(dispatchId: String, leaseOwner: String, code: String, next: Instant, now: Instant): Boolean
    fun markTerminal(dispatchId: String, leaseOwner: String, code: String, now: Instant): Boolean
    fun markObsolete(dispatchId: String, leaseOwner: String, now: Instant): Boolean
    fun audit(runId: String, event: String, code: String?, now: Instant)
}
