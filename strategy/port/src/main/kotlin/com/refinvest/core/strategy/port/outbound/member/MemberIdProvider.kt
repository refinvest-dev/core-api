package com.refinvest.core.strategy.port.outbound.member

import com.refinvest.core.shared.kernel.member.MemberId

/** Supplies the member executing a Strategy use case; authentication will provide this in production. */
fun interface MemberIdProvider {
    fun currentMemberId(): MemberId
}
