package com.refinvest.core.tradingreview.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "trading_import_sessions")
class TradingImportSessionJpaEntity(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: Long,
    @Column(name = "trading_book_id", nullable = false, updatable = false)
    var bookId: Long,
    @Column(name = "owner_member_id", nullable = false, updatable = false)
    var owner: Long,
    @Column(name = "source_timezone", nullable = false, updatable = false, length = 100)
    var sourceTimezone: String,
    @Column(name = "review_timezone", nullable = false, updatable = false, length = 100)
    var reviewTimezone: String,
    @Column(name = "declared_position_mode", nullable = false, updatable = false, length = 20)
    var declaredPositionMode: String,
    @Column(name = "data_policy_version", nullable = false, updatable = false, length = 100)
    var dataPolicyVersion: String,
    @Column(name = "retention_policy", nullable = false, updatable = false, length = 100)
    var retentionPolicy: String,
    @Column(name = "masking_policy_version", nullable = false, updatable = false, length = 100)
    var maskingPolicyVersion: String,
    @Column(name = "evidence_retention", nullable = false, updatable = false, length = 30)
    var evidenceRetention: String,
    @Column(name = "deletion_policy_version", nullable = false, updatable = false, length = 100)
    var deletionPolicyVersion: String,
    @Column(name = "version_set_json", nullable = false, updatable = false, columnDefinition = "text")
    var versionSetJson: String,
    @Column(name = "status", nullable = false, length = 20)
    var status: String,
    @Column(name = "deletion_generation", nullable = false)
    var deletionGeneration: Long,
    @Column(name = "deletion_pending", nullable = false)
    var deletionPending: Boolean,
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
    @Version
    @Column(name = "version", nullable = false)
    var version: Long,
    @Column(name = "validation_started_at", nullable = true)
    var validationStartedAt: Instant?,
    @Column(name = "active_normalization_run_id", nullable = true)
    var activeNormalizationRunId: UUID?,
    @Column(name = "created_ledger_revision_id", nullable = true, updatable = false)
    var createdLedgerRevisionId: UUID?,
    @Column(name = "accepted_at", nullable = true, updatable = false)
    var acceptedAt: Instant?,
    @Column(name = "rejected_at", nullable = true)
    var rejectedAt: Instant?,
    @Column(name = "raw_expired_at", nullable = true)
    var rawExpiredAt: Instant?,
    @Column(name = "failure_code", nullable = true, length = 80)
    var failureCode: String?,
    @Column(name = "failure_retryable", nullable = true)
    var failureRetryable: Boolean?,
    @Column(name = "failure_user_action", nullable = true, length = 30)
    var failureUserAction: String?,
    @Column(name = "failure_occurred_at", nullable = true)
    var failureOccurredAt: Instant?,
)
