package com.refinvest.core.member.adapter.out.persistence

import com.refinvest.core.member.domain.Member
import com.refinvest.core.member.domain.MemberRole
import com.refinvest.core.member.port.outbound.MemberReader
import com.refinvest.core.shared.kernel.member.MemberId
import org.springframework.stereotype.Repository

@Repository
class JpaMemberReaderAdapter(
    private val memberJpaStore: MemberJpaStore,
) : MemberReader {
    override fun findById(memberId: MemberId): Member? = memberJpaStore.findById(memberId.value).orElse(null)?.toDomain()

    private fun MemberJpaEntity.toDomain(): Member = Member.restore(MemberId(id), MemberRole.valueOf(role), createdAt)
}
