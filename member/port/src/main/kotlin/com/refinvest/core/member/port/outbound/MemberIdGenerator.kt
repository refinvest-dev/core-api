package com.refinvest.core.member.port.outbound

import com.refinvest.core.shared.kernel.member.MemberId

fun interface MemberIdGenerator {
    fun next(): MemberId
}
