package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.tradingreview.domain.valueobject.*
import java.time.Instant
import java.util.UUID

internal data class TradingImportSessionRecord(
    val id: Long,
    val bookId: Long,
    val owner: Long,
    val sourceTimezone: String,
    val reviewTimezone: String,
    val declaredPositionMode: String,
    val dataPolicyVersion: String,
    val retentionPolicy: String,
    val maskingPolicyVersion: String,
    val evidenceRetention: String,
    val deletionPolicyVersion: String,
    val versionSetJson: String,
    val status: String,
    val deletionGeneration: Long,
    val deletionPending: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
    val validationStartedAt: Instant?,
    val activeNormalizationRunId: String?,
    val failure: TradingImportFailureRecord?,
    val createdLedgerRevisionId: UUID?,
    val artifacts: List<TradingSourceArtifactRecord> = emptyList(),
)

internal data class TradingSourceArtifactRecord(
    val id: Long,
    val sessionId: Long,
    val role: String,
    val filename: String?,
    val contentType: String,
    val byteSize: Long,
    val sha256: String,
    val dialectCandidate: String,
    val objectKey: String?,
    val wrappedDek: ByteArray?,
    val keyVersion: String?,
    val retentionStatus: String,
    val uploadedAt: Instant,
    val rawExpiresAt: Instant,
    val deletedAt: Instant?,
    val deletionFailure: String?,
    val version: Long,
)

internal data class TradingImportFailureRecord(val code: String, val retryable: Boolean, val userAction: String, val occurredAt: Instant)
