package com.refinvest.core.tradingreview.adapter.persistence

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface TradingBookJpaStore : JpaRepository<TradingBookJpaEntity, Long> {
    @Query("select e from TradingBookJpaEntity e where e.id = :id and e.ownerMemberId = :owner")
    fun findOwned(id: Long, owner: Long): TradingBookJpaEntity?

    // Constructor projections bypass managed cache entries changed by JDBC, without a second refresh read.
    @Query(SNAPSHOT + " where e.id = :id and e.ownerMemberId = :owner")
    fun readSnapshot(id: Long, owner: Long): TradingBookJpaEntity?

    @Query(value = "select id from trading_books where id=:id and owner_member_id=:owner for update", nativeQuery = true)
    fun lockOwned(id: Long, owner: Long): Long?

    @Query(SNAPSHOT + " where e.ownerMemberId = :owner and e.accountId = :accountId and e.status = 'ACTIVE' order by e.createdAt desc, e.id desc",
        countQuery = "select count(e) from TradingBookJpaEntity e where e.ownerMemberId = :owner and e.accountId = :accountId and e.status = 'ACTIVE'")
    fun page(accountId: Long, owner: Long, pageable: Pageable): Page<TradingBookJpaEntity>

    @Query(value = "select exists(select 1 from trading_deleted_resources where target_type='TRADING_BOOK' and target_id=:id and owner_member_id=:owner)", nativeQuery = true)
    fun deleted(id: Long, owner: Long): Boolean
}

private const val SNAPSHOT = "select new com.refinvest.core.tradingreview.adapter.persistence.TradingBookJpaEntity(e.id, e.accountId, e.ownerMemberId, e.venue, e.displayName, e.productFamily, e.positionMode, e.settlementAsset, e.reviewTimezone, e.status, e.deletionGeneration, e.createdAt, e.updatedAt, e.version, e.latestLedgerRevisionId) from TradingBookJpaEntity e"
