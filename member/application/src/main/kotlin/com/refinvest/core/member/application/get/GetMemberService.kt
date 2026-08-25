package com.refinvest.core.member.application.get

import com.refinvest.core.member.port.inbound.get.GetMemberQuery
import com.refinvest.core.member.port.inbound.get.GetMemberResult
import com.refinvest.core.member.port.inbound.get.GetMemberUseCase
import com.refinvest.core.member.port.outbound.persistence.MemberReader
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
open class GetMemberService(
    private val memberReader: MemberReader,
) : GetMemberUseCase {
    @Transactional(readOnly = true)
    override fun execute(query: GetMemberQuery): GetMemberResult? = memberReader.findById(query.memberId)
        ?.let { member -> GetMemberResult(member.id, member.role) }
}
