package com.refinvest.core.member.adapter.out.persistence

import com.refinvest.core.member.domain.Member
import com.refinvest.core.member.port.outbound.persistence.MemberStore
import org.springframework.stereotype.Repository

@Repository
class JpaMemberStoreAdapter(
    private val memberJpaStore: MemberJpaStore,
) : MemberStore {
    override fun save(member: Member) {
        memberJpaStore.save(MemberPersistenceMapper.toEntity(member))
    }
}
