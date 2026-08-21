package com.refinvest.core.subscription.port.outbound

import com.refinvest.core.subscription.domain.Subscription

fun interface SubscriptionStore {
    fun save(subscription: Subscription)
}
