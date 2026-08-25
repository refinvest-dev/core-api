package com.refinvest.core.auth.port.outbound.member

import com.refinvest.core.shared.kernel.member.MemberId

fun interface CurrentMemberIdProvider {
    fun currentMemberId(): MemberId
}
