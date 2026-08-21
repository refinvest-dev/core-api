package com.refinvest.core.auth.port.outbound

import com.refinvest.core.shared.kernel.member.MemberId
import java.util.UUID

data class RefreshTokenClaims(
    val jti: UUID,
    val memberId: MemberId,
    val familyId: UUID,
    val tokenFingerprint: String,
)
