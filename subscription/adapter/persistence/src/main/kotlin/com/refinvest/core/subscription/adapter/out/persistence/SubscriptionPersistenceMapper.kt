package com.refinvest.core.subscription.adapter.out.persistence

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.subscription.domain.Subscription
import com.refinvest.core.subscription.domain.valueobject.SubscriptionTier

internal object SubscriptionPersistenceMapper {
    fun toEntity(subscription: Subscription): SubscriptionJpaEntity =
        SubscriptionJpaEntity(subscription.id.value, subscription.tier.name)

    fun toDomain(entity: SubscriptionJpaEntity): Subscription =
        Subscription.restore(MemberId(entity.memberId), SubscriptionTier.valueOf(entity.tier))
}
