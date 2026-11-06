package com.refinvest.core.subscription.port.outbound.persistence

import com.refinvest.core.subscription.domain.Subscription
import com.refinvest.core.shared.kernel.member.MemberId

interface SubscriptionStore {
    /** Save a new aggregate or one loaded for change in the same command transaction. */
    fun save(subscription: Subscription)

    /** Serialize command access for this member, including when its subscription does not yet exist. */
    fun findByMemberId(memberId: MemberId): Subscription?
}
