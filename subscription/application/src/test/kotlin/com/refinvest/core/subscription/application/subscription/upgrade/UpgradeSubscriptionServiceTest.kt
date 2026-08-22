package com.refinvest.core.subscription.application.subscription.upgrade

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.subscription.domain.Subscription
import com.refinvest.core.subscription.domain.valueobject.SubscriptionTier
import com.refinvest.core.subscription.port.inbound.subscription.upgrade.UpgradeSubscriptionCommand
import com.refinvest.core.subscription.port.outbound.SubscriptionStore
import kotlin.test.Test
import kotlin.test.assertEquals

class UpgradeSubscriptionServiceTest {
    @Test
    fun `upgrades a free subscription to pro`() {
        val memberId = MemberId(1)
        val store = InMemorySubscriptionStore(Subscription.createDefault(memberId))
        val service = UpgradeSubscriptionService(store)

        val result = service.execute(UpgradeSubscriptionCommand(memberId))

        assertEquals(memberId, result.memberId)
        assertEquals(SubscriptionTier.PRO, result.tier)
        assertEquals(SubscriptionTier.PRO, store.subscription?.tier)
    }

    @Test
    fun `keeps an existing pro subscription at pro`() {
        val memberId = MemberId(1)
        val store = InMemorySubscriptionStore(Subscription.restore(memberId, SubscriptionTier.PRO))
        val service = UpgradeSubscriptionService(store)

        val result = service.execute(UpgradeSubscriptionCommand(memberId))

        assertEquals(SubscriptionTier.PRO, result.tier)
        assertEquals(SubscriptionTier.PRO, store.subscription?.tier)
    }

    private class InMemorySubscriptionStore(
        var subscription: Subscription?,
    ) : SubscriptionStore {
        override fun save(subscription: Subscription) {
            this.subscription = subscription
        }

        override fun findByMemberId(memberId: MemberId): Subscription? = subscription
            ?.takeIf { it.id == memberId }
    }
}
