package com.refinvest.core.subscription.port.inbound.create

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.subscription.domain.valueobject.SubscriptionTier

data class CreateSubscriptionResult(
    val memberId: MemberId,
    val tier: SubscriptionTier,
)
