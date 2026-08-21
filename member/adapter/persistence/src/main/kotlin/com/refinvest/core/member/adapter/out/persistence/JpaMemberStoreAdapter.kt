package com.refinvest.core.member.adapter.out.persistence

import com.refinvest.core.member.domain.Member
import com.refinvest.core.member.port.outbound.MemberStore
import org.springframework.stereotype.Repository

@Repository
class JpaMemberStoreAdapter(
    private val memberJpaStore: MemberJpaStore,
) : MemberStore {
    override fun save(member: Member) {
        memberJpaStore.save(MemberJpaEntity(member.id.value, member.role.name, member.createdAt))
    }
}
