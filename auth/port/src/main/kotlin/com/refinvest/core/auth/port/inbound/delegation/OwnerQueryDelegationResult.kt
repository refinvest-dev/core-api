package com.refinvest.core.auth.port.inbound.delegation

import java.time.Instant

class OwnerQueryDelegationResult(
    val compactToken: String,
    val issuedAt: Instant,
    val expiresAt: Instant,
) {
    override fun toString(): String = "OwnerQueryDelegationResult(redacted)"
}
