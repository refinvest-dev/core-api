package com.refinvest.core.member.adapter.out.persistence

import org.springframework.data.repository.Repository

interface MemberJpaStore : Repository<MemberJpaEntity, Long> {
    fun save(member: MemberJpaEntity): MemberJpaEntity
}
