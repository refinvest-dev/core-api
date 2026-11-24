package com.refinvest.core.tradingreview.port.outbound.ownerquery

import java.time.Instant

class OwnerQueryDelegation(val compactToken: String, val expiresAt: Instant) {
    override fun toString(): String = "OwnerQueryDelegation(redacted)"
}
