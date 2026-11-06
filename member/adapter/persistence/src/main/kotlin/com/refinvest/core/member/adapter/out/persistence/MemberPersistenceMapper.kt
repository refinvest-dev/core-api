package com.refinvest.core.member.adapter.out.persistence

import com.refinvest.core.member.domain.Member
import com.refinvest.core.member.domain.valueobject.MemberRole
import com.refinvest.core.shared.kernel.member.MemberId

internal object MemberPersistenceMapper {
    fun toEntity(member: Member): MemberJpaEntity =
        MemberJpaEntity(member.id.value, member.role.name, member.createdAt)

    fun toDomain(entity: MemberJpaEntity): Member =
        Member.restore(MemberId(entity.id), MemberRole.valueOf(entity.role), entity.createdAt)
}
