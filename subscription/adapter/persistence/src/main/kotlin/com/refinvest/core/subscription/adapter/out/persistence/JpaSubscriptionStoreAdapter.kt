package com.refinvest.core.subscription.adapter.out.persistence

import com.refinvest.core.subscription.domain.Subscription
import com.refinvest.core.subscription.port.outbound.SubscriptionStore
import org.springframework.stereotype.Repository

@Repository
class JpaSubscriptionStoreAdapter(
    private val subscriptionJpaStore: SubscriptionJpaStore,
) : SubscriptionStore {
    override fun save(subscription: Subscription) {
        subscriptionJpaStore.save(SubscriptionJpaEntity(subscription.id.value, subscription.tier.name))
    }
}
