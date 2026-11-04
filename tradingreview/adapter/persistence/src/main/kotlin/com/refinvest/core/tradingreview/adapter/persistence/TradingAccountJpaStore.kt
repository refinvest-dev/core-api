package com.refinvest.core.tradingreview.adapter.persistence

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface TradingAccountJpaStore : JpaRepository<TradingAccountJpaEntity, Long> {
    @Query("select e from TradingAccountJpaEntity e where e.id = :id and e.ownerMemberId = :owner")
    fun findOwned(id: Long, owner: Long): TradingAccountJpaEntity?

    // Constructor projections bypass managed cache entries changed by JDBC, without a second refresh read.
    @Query(SNAPSHOT + " where e.id = :id and e.ownerMemberId = :owner")
    fun readSnapshot(id: Long, owner: Long): TradingAccountJpaEntity?

    @Query(value = "select id from trading_accounts where id=:id and owner_member_id=:owner for update", nativeQuery = true)
    fun lockOwned(id: Long, owner: Long): Long?

    @Query(SNAPSHOT + " where e.ownerMemberId = :owner and e.status in ('ACTIVE', 'ARCHIVED') order by e.createdAt desc, e.id desc",
        countQuery = "select count(e) from TradingAccountJpaEntity e where e.ownerMemberId = :owner and e.status in ('ACTIVE', 'ARCHIVED')")
    fun page(owner: Long, pageable: Pageable): Page<TradingAccountJpaEntity>

    @Query(value = "select exists(select 1 from trading_deleted_resources where target_type='TRADING_ACCOUNT' and target_id=:id and owner_member_id=:owner)", nativeQuery = true)
    fun deleted(id: Long, owner: Long): Boolean
}

private const val SNAPSHOT = "select new com.refinvest.core.tradingreview.adapter.persistence.TradingAccountJpaEntity(e.id, e.ownerMemberId, e.venue, e.displayName, e.status, e.deletionGeneration, e.createdAt, e.updatedAt, e.version) from TradingAccountJpaEntity e"
