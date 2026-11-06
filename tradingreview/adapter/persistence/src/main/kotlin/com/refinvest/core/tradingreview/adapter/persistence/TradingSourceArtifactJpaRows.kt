package com.refinvest.core.tradingreview.adapter.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface TradingSourceArtifactJpaRows : JpaRepository<TradingSourceArtifactJpaEntity, Long> {
    @Query(SNAPSHOT + " join TradingImportSessionJpaEntity s on s.id=e.sessionId where e.id=:id and s.owner=:owner")
    fun readOwned(id: Long, owner: Long): TradingSourceArtifactJpaEntity?

    @Query(SNAPSHOT + " where e.sessionId=:sessionId order by e.role")
    fun readSession(sessionId: Long): List<TradingSourceArtifactJpaEntity>

    @Query(SNAPSHOT + " where e.sessionId=:sessionId and e.role=:role")
    fun readRole(sessionId: Long, role: String): TradingSourceArtifactJpaEntity?

    @Query(value = "select id from trading_source_artifacts where import_session_id=:sessionId order by artifact_role for update", nativeQuery = true)
    fun lockSlots(sessionId: Long): List<Long>

    fun countByObjectKey(key: String): Long
}

private const val SNAPSHOT = "select new com.refinvest.core.tradingreview.adapter.persistence.TradingSourceArtifactJpaEntity(e.id, e.sessionId, e.role, e.filename, e.contentType, e.byteSize, e.sha256, e.dialectCandidate, e.objectKey, e.wrappedDek, e.keyVersion, e.retentionStatus, e.uploadedAt, e.rawExpiresAt, e.deletedAt, e.deletionFailure, e.version, e.uploadStatus) from TradingSourceArtifactJpaEntity e"
