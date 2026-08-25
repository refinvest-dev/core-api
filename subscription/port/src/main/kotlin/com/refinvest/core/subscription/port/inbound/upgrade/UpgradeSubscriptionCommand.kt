package com.refinvest.core.subscription.port.inbound.upgrade

import com.refinvest.core.shared.kernel.member.MemberId

data class UpgradeSubscriptionCommand(
    val memberId: MemberId,
)
