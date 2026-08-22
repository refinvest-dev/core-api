package com.refinvest.core.subscription.domain

import com.refinvest.core.common.domain.AggregateRoot
import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.subscription.domain.valueobject.SubscriptionTier

class Subscription private constructor(
    memberId: MemberId,
    val tier: SubscriptionTier,
) : AggregateRoot<MemberId>(memberId) {
    fun upgrade(): Subscription = when (tier) {
        SubscriptionTier.FREE -> Subscription(id, SubscriptionTier.PRO)
        SubscriptionTier.PRO -> this
    }

    companion object {
        fun createDefault(memberId: MemberId): Subscription = Subscription(memberId, SubscriptionTier.FREE)

        fun restore(memberId: MemberId, tier: SubscriptionTier): Subscription = Subscription(memberId, tier)
    }
}
