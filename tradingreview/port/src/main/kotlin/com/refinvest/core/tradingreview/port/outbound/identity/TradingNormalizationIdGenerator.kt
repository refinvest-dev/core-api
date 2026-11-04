package com.refinvest.core.tradingreview.port.outbound.identity

import com.refinvest.core.tradingreview.domain.valueobject.NormalizationRunId

interface TradingNormalizationIdGenerator {
    fun runId(): NormalizationRunId
    /** Durable dispatch identity and Compute create idempotency scope. */
    fun dispatchId(): String
    /** Core/Compute execution identity, independent of a worker's claim lease owner. */
    fun attemptToken(): String
    /** Independent idempotency key for a grant-update request; not an execution identity. */
    fun grantUpdateKey(): String
}
