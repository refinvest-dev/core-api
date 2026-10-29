package com.refinvest.core.member.adapter.out.persistence

import com.refinvest.core.member.domain.Member
import com.refinvest.core.member.port.outbound.persistence.MemberReader
import com.refinvest.core.shared.kernel.member.MemberId
import org.springframework.stereotype.Repository

@Repository
class JpaMemberReaderAdapter(
    private val memberJpaReader: MemberJpaReader,
) : MemberReader {
    override fun findById(memberId: MemberId): Member? = memberJpaReader.findById(memberId.value)
        ?.let(MemberPersistenceMapper::toDomain)
}
