package com.refinvest.core.subscription.port.outbound.persistence

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.subscription.domain.Subscription

fun interface SubscriptionReader {
    fun findByMemberId(memberId: MemberId): Subscription?
}
