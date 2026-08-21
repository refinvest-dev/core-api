package com.refinvest.core.member.port.outbound

import com.refinvest.core.member.domain.Member

fun interface MemberStore {
    fun save(member: Member)
}
