package com.refinvest.core.tradingreview.domain.importsession

import com.refinvest.core.tradingreview.domain.*
import com.refinvest.core.tradingreview.domain.valueobject.*
import java.time.Instant

data class NormalizationDispatch(
    val id: String,
    val runId: String,
    val computeKey: String,
    val status: NormalizationDispatchStatus,
    val attemptCount: Int,
    val nextAttemptAt: Instant,
    val leaseOwner: String?,
    val leaseExpiresAt: Instant?,
    val lastFailureCode: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val pollFailureCount: Int = 0,
    val resultVersionPin: NormalizationResultVersionPin = NormalizationResultVersionPin.V1,
)
