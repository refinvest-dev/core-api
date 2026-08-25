package com.refinvest.core.member.adapter.out.persistence

import org.springframework.data.repository.Repository

interface MemberJpaReader : Repository<MemberJpaEntity, Long> {
    fun findById(id: Long): MemberJpaEntity?
}
