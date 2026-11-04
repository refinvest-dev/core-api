package com.refinvest.core.tradingreview.adapter.persistence

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface TradingImportSessionJpaStore : JpaRepository<TradingImportSessionJpaEntity, Long> {
    @Query(SNAPSHOT + " where e.id=:id and e.owner=:owner")
    fun readSnapshot(id: Long, owner: Long): TradingImportSessionJpaEntity?

    @Query(value = "select id from trading_import_sessions where id=:id and owner_member_id=:owner for update", nativeQuery = true)
    fun lockOwned(id: Long, owner: Long): Long?

    @Query(SNAPSHOT + " where e.bookId=:bookId and e.owner=:owner and e.deletionPending=false order by e.createdAt desc,e.id desc",
        countQuery = "select count(e) from TradingImportSessionJpaEntity e where e.bookId=:bookId and e.owner=:owner and e.deletionPending=false")
    fun page(bookId: Long, owner: Long, pageable: Pageable): Page<TradingImportSessionJpaEntity>

    @Query(value = "select exists(select 1 from trading_deleted_resources where target_type='TRADING_IMPORT_SESSION' and target_id=:id and owner_member_id=:owner)", nativeQuery = true)
    fun deleted(id: Long, owner: Long): Boolean
}

private const val SNAPSHOT = "select new com.refinvest.core.tradingreview.adapter.persistence.TradingImportSessionJpaEntity(e.id, e.bookId, e.owner, e.sourceTimezone, e.reviewTimezone, e.declaredPositionMode, e.dataPolicyVersion, e.retentionPolicy, e.maskingPolicyVersion, e.evidenceRetention, e.deletionPolicyVersion, e.versionSetJson, e.status, e.deletionGeneration, e.deletionPending, e.createdAt, e.updatedAt, e.version, e.validationStartedAt, e.activeNormalizationRunId, e.createdLedgerRevisionId, e.acceptedAt, e.rejectedAt, e.rawExpiredAt, e.failureCode, e.failureRetryable, e.failureUserAction, e.failureOccurredAt) from TradingImportSessionJpaEntity e"
