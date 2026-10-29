package com.refinvest.core.auth.adapter.out.persistence

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param
import java.util.UUID

interface RefreshSessionFamilyLockJpaStore : Repository<RefreshSessionFamilyLockJpaEntity, UUID> {
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO refresh_session_family_locks (family_id) VALUES (:familyId) ON CONFLICT DO NOTHING", nativeQuery = true)
    fun createIfMissing(@Param("familyId") familyId: UUID): Int

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select fence from RefreshSessionFamilyLockJpaEntity fence where fence.familyId=:familyId")
    fun lockById(@Param("familyId") familyId: UUID): RefreshSessionFamilyLockJpaEntity?
}
