package com.refinvest.core.auth.port.inbound.me

import com.refinvest.core.shared.kernel.member.MemberId

data class GetCurrentMemberResult(
    val memberId: MemberId,
    val role: String,
)
