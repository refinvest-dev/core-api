package com.refinvest.core.member.domain

import com.refinvest.core.common.domain.AggregateRoot
import com.refinvest.core.shared.kernel.member.MemberId
import java.time.Instant

class Member private constructor(
    id: MemberId,
    val role: MemberRole,
    val createdAt: Instant,
) : AggregateRoot<MemberId>(id) {
    companion object {
        fun create(id: MemberId, createdAt: Instant): Member = Member(id, MemberRole.MEMBER, createdAt)

        fun restore(id: MemberId, role: MemberRole, createdAt: Instant): Member = Member(id, role, createdAt)
    }
}
