package com.refinvest.core.subscription.port.outbound.persistence

import com.refinvest.core.subscription.domain.Subscription
import com.refinvest.core.shared.kernel.member.MemberId

interface SubscriptionStore {
    fun save(subscription: Subscription)

    fun findByMemberId(memberId: MemberId): Subscription?
}
