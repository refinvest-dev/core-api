package com.refinvest.core.subscription.port.inbound.create

import com.refinvest.core.shared.kernel.member.MemberId

data class CreateSubscriptionCommand(
    val memberId: MemberId,
)
