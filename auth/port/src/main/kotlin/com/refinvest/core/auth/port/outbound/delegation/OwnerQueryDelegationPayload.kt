package com.refinvest.core.auth.port.outbound.delegation

import com.refinvest.core.shared.kernel.member.MemberId
import java.time.Instant

class OwnerQueryDelegationPayload(
    val memberId: MemberId,
    val tokenId: String,
    val issuedAt: Instant,
) {
    override fun toString(): String = "OwnerQueryDelegationPayload(redacted)"

    companion object {
        const val TTL_SECONDS = 60L
    }
}
