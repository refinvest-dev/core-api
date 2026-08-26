package com.refinvest.core.subscription.port.inbound.get

import com.refinvest.core.shared.kernel.member.MemberId

data class GetSubscriptionQuery(
    val memberId: MemberId,
)
