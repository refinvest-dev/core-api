package com.refinvest.core.subscription.adapter.out.persistence

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.subscription.domain.valueobject.SubscriptionTier
import kotlin.test.*

class SubscriptionPersistenceMapperTest {
    @Test
    fun `both tiers survive restoration and upgrade only changes the returned aggregate`() {
        SubscriptionTier.entries.forEach { tier ->
            val stored = SubscriptionJpaEntity(9007199254740993L, tier.name)
            val subscription = SubscriptionPersistenceMapper.toDomain(stored)
            val roundTrip = SubscriptionPersistenceMapper.toEntity(subscription)
            assertEquals(MemberId(stored.memberId), subscription.id)
            assertEquals(tier, subscription.tier)
            assertEquals(stored.memberId, roundTrip.memberId)
            assertEquals(stored.tier, roundTrip.tier)
            val upgraded = subscription.upgrade()
            assertEquals(subscription.id, upgraded.id)
            assertEquals(SubscriptionTier.PRO, upgraded.tier)
            assertEquals(tier, subscription.tier)
            assertSame(upgraded, upgraded.upgrade())
        }
    }

    @Test
    fun `unknown persisted tier fails instead of silently resetting entitlements`() {
        assertFailsWith<IllegalArgumentException> {
            SubscriptionPersistenceMapper.toDomain(SubscriptionJpaEntity(1, "UNKNOWN"))
        }
    }
}
