package com.refinvest.core.subscription.port.inbound.subscription.usage

import com.refinvest.core.shared.kernel.member.MemberId

data class GetUsageQuery(
    val memberId: MemberId,
)
