package com.refinvest.core.auth.port.inbound.auth.session

import com.refinvest.core.shared.kernel.member.MemberId
import java.util.UUID

data class RefreshSessionCommand(
    val jti: UUID,
    val memberId: MemberId,
    val familyId: UUID,
    val tokenFingerprint: String,
)
