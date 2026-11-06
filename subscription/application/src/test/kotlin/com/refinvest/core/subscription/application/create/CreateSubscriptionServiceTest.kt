package com.refinvest.core.subscription.application.create

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.subscription.domain.Subscription
import com.refinvest.core.subscription.domain.valueobject.SubscriptionTier
import com.refinvest.core.subscription.port.inbound.create.CreateSubscriptionCommand
import com.refinvest.core.subscription.port.outbound.persistence.SubscriptionStore
import kotlin.test.*

class CreateSubscriptionServiceTest {
    @Test
    fun `creates a free aggregate only when none exists`() {
        val store = RecordingStore()
        val result = CreateSubscriptionService(store).execute(CreateSubscriptionCommand(MemberId(1)))
        assertEquals(MemberId(1), result.memberId)
        assertEquals("FREE", result.tier.name)
        assertEquals(listOf(SubscriptionTier.FREE), store.saved.map { it.tier })
    }

    @Test
    fun `repeated creation returns existing free or pro without saving a replacement`() {
        SubscriptionTier.entries.forEach { tier ->
            val store = RecordingStore(Subscription.restore(MemberId(1), tier))
            val result = CreateSubscriptionService(store).execute(CreateSubscriptionCommand(MemberId(1)))
            assertEquals(MemberId(1), result.memberId)
            assertEquals(tier.name, result.tier.name)
            assertTrue(store.saved.isEmpty())
        }
    }

    private class RecordingStore(private val existing: Subscription? = null) : SubscriptionStore {
        val saved = mutableListOf<Subscription>()
        override fun findByMemberId(memberId: MemberId): Subscription? = existing?.takeIf { it.id == memberId }
        override fun save(subscription: Subscription) { saved += subscription }
    }
}
