package com.refinvest.core.tradingreview.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant

@Entity
@Table(name = "trading_deletion_requests")
class DeletionRequestJpaEntity(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: Long,
    @Column(name = "owner_member_id", nullable = false, updatable = false)
    var ownerId: Long,
    @Column(name = "target_type", nullable = false, updatable = false, length = 30)
    var targetType: String,
    @Column(name = "target_id", nullable = false, updatable = false)
    var targetId: Long,
    @Column(name = "requested_scope", nullable = false, updatable = false, length = 30)
    var requestedScope: String,
    @Column(name = "state", nullable = false, updatable = false, length = 30)
    var state: String,
    @Column(name = "deletion_generation", nullable = false, updatable = false)
    var deletionGeneration: Long,
    @Column(name = "account_count", nullable = false, updatable = false)
    var accountCount: Long,
    @Column(name = "book_count", nullable = false, updatable = false)
    var bookCount: Long,
    @Column(name = "import_session_count", nullable = false, updatable = false)
    var importSessionCount: Long,
    @Column(name = "artifact_count", nullable = false, updatable = false)
    var artifactCount: Long,
    @Column(name = "revoked_at", nullable = false, updatable = false)
    var revokedAt: Instant,
    @Column(name = "requested_at", nullable = false, updatable = false)
    var requestedAt: Instant,
    @Column(name = "updated_at", nullable = false, updatable = false)
    var updatedAt: Instant,
    @Column(name = "backup_purge_due_at", nullable = false, updatable = false)
    var backupPurgeDueAt: Instant,
    @Column(name = "completed_at", nullable = true, updatable = false)
    var completedAt: Instant?,
    @Column(name = "failure_code", nullable = true, updatable = false, length = 80)
    var failureCode: String?,
    @Column(name = "attempt_count", nullable = false, updatable = false)
    var attemptCount: Int,
    @Version
    @Column(name = "version", nullable = false)
    var version: Long,
    @Column(name = "ledger_revision_count", nullable = false, updatable = false)
    var ledgerRevisionCount: Long,
    @Column(name = "analysis_run_count", nullable = false, updatable = false)
    var analysisRunCount: Long,
    @Column(name = "reprocessing_run_count", nullable = false, updatable = false)
    var reprocessingRunCount: Long,
    @Column(name = "count_snapshot_status", nullable = false, updatable = false, length = 30)
    var countSnapshotStatus: String,
    @Column(name = "remediation_of", nullable = true, updatable = false)
    var remediationOf: Long? = null,
)
