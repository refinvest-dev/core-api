package com.refinvest.core.member.port.inbound.member.get

import com.refinvest.core.member.domain.MemberRole
import com.refinvest.core.shared.kernel.member.MemberId

data class GetMemberResult(
    val memberId: MemberId,
    val role: MemberRole,
)
