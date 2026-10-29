package com.refinvest.core.subscription.port.inbound.policy

import com.refinvest.core.shared.kernel.member.MemberId

data class GetBacktestPolicyQuery(val memberId: MemberId)
