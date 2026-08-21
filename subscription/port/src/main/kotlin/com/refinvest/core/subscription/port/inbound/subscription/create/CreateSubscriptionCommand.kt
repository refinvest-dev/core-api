package com.refinvest.core.subscription.port.inbound.subscription.create

import com.refinvest.core.shared.kernel.member.MemberId

data class CreateSubscriptionCommand(
    val memberId: MemberId,
)
