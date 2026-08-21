package com.refinvest.core.auth.adapter.out.persistence

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.data.repository.CrudRepository
import java.util.UUID

interface RefreshSessionJpaStore : CrudRepository<RefreshSessionJpaEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from RefreshSessionJpaEntity session where session.jti = :jti")
    fun findByIdForUpdate(@Param("jti") jti: UUID): RefreshSessionJpaEntity?

    fun findAllByFamilyIdAndRevokedAtIsNull(familyId: UUID): List<RefreshSessionJpaEntity>
}
