package com.refinvest.core.tradingreview.port.outbound.idempotency

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.*

interface TradingIdempotencyStore {
    fun claim(owner: MemberId, operation: String, key: String, fingerprint: String): IdempotencyClaim
    fun complete(owner: MemberId, operation: String, key: String, resourceId: Long)
}
