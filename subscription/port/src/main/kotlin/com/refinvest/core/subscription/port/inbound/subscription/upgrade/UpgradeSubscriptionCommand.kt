package com.refinvest.core.subscription.port.inbound.subscription.upgrade

import com.refinvest.core.shared.kernel.member.MemberId

data class UpgradeSubscriptionCommand(
    val memberId: MemberId,
)
