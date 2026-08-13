package com.refinvest.core.strategy.port.outbound

import com.refinvest.core.strategy.domain.MemberId

/** Supplies the member executing a Strategy use case; authentication will provide this in production. */
fun interface MemberIdProvider {
    fun currentMemberId(): MemberId
}
