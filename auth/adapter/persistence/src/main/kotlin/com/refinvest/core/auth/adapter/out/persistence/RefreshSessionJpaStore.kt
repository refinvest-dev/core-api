package com.refinvest.core.auth.adapter.out.persistence

import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.data.repository.CrudRepository
import java.time.Instant
import java.util.UUID

interface RefreshSessionJpaStore : CrudRepository<RefreshSessionJpaEntity, UUID> {
    @Modifying(flushAutomatically = true)
    @Query("update RefreshSessionJpaEntity session set session.revokedAt=:revokedAt where session.familyId=:familyId and session.revokedAt is null")
    fun revokeActiveFamily(@Param("familyId") familyId: UUID, @Param("revokedAt") revokedAt: Instant): Int
}
