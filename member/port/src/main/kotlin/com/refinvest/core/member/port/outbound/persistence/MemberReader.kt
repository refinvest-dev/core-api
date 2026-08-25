package com.refinvest.core.member.port.outbound.persistence

import com.refinvest.core.member.domain.Member
import com.refinvest.core.shared.kernel.member.MemberId

fun interface MemberReader {
    fun findById(memberId: MemberId): Member?
}
