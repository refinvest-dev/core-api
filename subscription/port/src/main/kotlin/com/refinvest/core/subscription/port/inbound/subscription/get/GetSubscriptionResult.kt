package com.refinvest.core.subscription.port.inbound.subscription.get

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.subscription.domain.valueobject.SubscriptionTier

data class GetSubscriptionResult(
    val memberId: MemberId,
    val tier: SubscriptionTier,
)
