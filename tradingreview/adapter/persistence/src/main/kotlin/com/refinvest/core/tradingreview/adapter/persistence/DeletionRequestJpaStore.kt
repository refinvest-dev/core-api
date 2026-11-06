package com.refinvest.core.tradingreview.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface DeletionRequestJpaStore : JpaRepository<DeletionRequestJpaEntity, Long> {
    @Query(SNAPSHOT + " where e.id=:id and e.ownerId=:owner")
    fun readOwned(id: Long, owner: Long): List<DeletionRequestSnapshotRow>

    @Query(SNAPSHOT + " where e.ownerId=:owner and e.targetType=:type and e.targetId=:target and e.remediationOf is null")
    fun readOriginalTarget(owner: Long, type: String, target: Long): List<DeletionRequestSnapshotRow>
}

private const val SNAPSHOT = "select new com.refinvest.core.tradingreview.adapter.persistence.DeletionRequestSnapshotRow(e.id, e.ownerId, e.targetType, e.targetId, e.requestedScope, e.state, e.deletionGeneration, e.accountCount, e.bookCount, e.importSessionCount, e.artifactCount, e.revokedAt, e.requestedAt, e.updatedAt, e.backupPurgeDueAt, e.completedAt, e.failureCode, e.attemptCount, e.version, e.ledgerRevisionCount, e.analysisRunCount, e.reprocessingRunCount, e.countSnapshotStatus,c.component,c.verifiedAt) from DeletionRequestJpaEntity e left join DeletionCheckpointJpaEntity c on c.requestId=e.id"
