package com.refinvest.core.auth.port.outbound.persistence

import com.refinvest.core.auth.domain.RefreshSession
import com.refinvest.core.auth.domain.valueobject.RefreshSessionRotation
import java.util.UUID

interface RefreshSessionStore {
    /** A snapshot; rotate must verify the expected state atomically before accepting it. */
    fun findByJti(jti: UUID): RefreshSession?
    fun save(session: RefreshSession)
    fun rotate(rotation: RefreshSessionRotation): Boolean
    fun revokeFamily(revocation: RefreshFamilyRevocation)
}
