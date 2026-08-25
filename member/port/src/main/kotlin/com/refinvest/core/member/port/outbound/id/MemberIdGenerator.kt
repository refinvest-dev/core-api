package com.refinvest.core.member.port.outbound.id

import com.refinvest.core.shared.kernel.member.MemberId

fun interface MemberIdGenerator {
    fun next(): MemberId
}
