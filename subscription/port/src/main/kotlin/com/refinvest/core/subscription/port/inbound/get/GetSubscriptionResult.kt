package com.refinvest.core.subscription.port.inbound.get

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.subscription.domain.valueobject.SubscriptionTier

data class GetSubscriptionResult(
    val memberId: MemberId,
    val tier: SubscriptionTier,
)
