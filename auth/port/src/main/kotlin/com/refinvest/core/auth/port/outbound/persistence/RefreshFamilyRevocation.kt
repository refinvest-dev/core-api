package com.refinvest.core.auth.port.outbound.persistence

import java.time.Instant
import java.util.UUID

data class RefreshFamilyRevocation(val familyId: UUID, val revokedAt: Instant)
