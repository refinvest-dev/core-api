package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.tradingreview.domain.*
import com.refinvest.core.tradingreview.domain.importsession.*
import com.refinvest.core.tradingreview.domain.valueobject.*
import java.time.Instant

internal data class NormalizationRunRecord(
    val id: String,
    val retryOfRunId: String?,
    val importSessionId: Long,
    val tradingBookId: Long,
    val ownerMemberId: Long,
    val inputFingerprint: String,
    val logicalKey: String,
    val inputJson: String,
    val inputIdentityJson: String?,
    val targetVersionSetJson: String,
    val instrumentTermsJson: String,
    val dataPolicyVersion: String,
    val retryPolicyVersion: String,
    val retryPolicyJson: String,
    val resultDeliveryPolicyJson: String?,
    val deletionGeneration: Long,
    val status: String,
    val currentAttempt: Int,
    val attemptToken: String,
    val computeJobId: String?,
    val lastFailureCode: String?,
    val nextAttemptAt: Instant,
    val createdAt: Instant,
    val startedAt: Instant?,
    val updatedAt: Instant,
    val completedAt: Instant?,
    val version: Long,
    val resultSchemaVersion: String,
    val resultHashSpecificationVersion: String,
)
