package com.refinvest.core.member.port.inbound.create

import com.refinvest.core.member.domain.MemberRole
import com.refinvest.core.shared.kernel.member.MemberId

data class CreateMemberResult(
    val memberId: MemberId,
    val role: MemberRole,
)
