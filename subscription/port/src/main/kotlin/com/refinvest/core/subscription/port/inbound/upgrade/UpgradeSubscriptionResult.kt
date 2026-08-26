package com.refinvest.core.subscription.port.inbound.upgrade

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.subscription.domain.valueobject.SubscriptionTier

data class UpgradeSubscriptionResult(
    val memberId: MemberId,
    val tier: SubscriptionTier,
)
