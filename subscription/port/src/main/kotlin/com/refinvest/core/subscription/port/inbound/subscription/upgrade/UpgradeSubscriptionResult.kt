package com.refinvest.core.subscription.port.inbound.subscription.upgrade

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.subscription.domain.valueobject.SubscriptionTier

data class UpgradeSubscriptionResult(
    val memberId: MemberId,
    val tier: SubscriptionTier,
)
