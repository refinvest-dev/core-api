package com.refinvest.core.auth.port.outbound

import com.refinvest.core.auth.domain.RefreshSession
import java.time.Instant
import java.util.UUID

interface RefreshSessionStore {
    fun findByJti(jti: UUID): RefreshSession?

    fun save(session: RefreshSession)

    /** Atomically marks [current] as rotated and stores [replacement]. */
    fun rotate(current: RefreshSession, replacement: RefreshSession, revokedAt: Instant): Boolean

    fun revokeFamily(familyId: UUID, revokedAt: Instant)
}
