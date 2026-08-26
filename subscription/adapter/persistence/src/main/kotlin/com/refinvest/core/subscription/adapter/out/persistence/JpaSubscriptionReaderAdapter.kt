package com.refinvest.core.subscription.adapter.out.persistence

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.subscription.domain.Subscription
import com.refinvest.core.subscription.domain.valueobject.SubscriptionTier
import com.refinvest.core.subscription.port.outbound.persistence.SubscriptionReader
import org.springframework.stereotype.Repository

@Repository
class JpaSubscriptionReaderAdapter(
    private val subscriptionJpaReader: SubscriptionJpaReader,
) : SubscriptionReader {
    override fun findByMemberId(memberId: MemberId): Subscription? = subscriptionJpaReader
        .findByMemberId(memberId.value)
        ?.let { entity -> Subscription.restore(MemberId(entity.memberId), SubscriptionTier.valueOf(entity.tier)) }
}
