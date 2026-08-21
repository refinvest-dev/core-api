package com.refinvest.core.member.application.member.get

import com.refinvest.core.member.port.inbound.member.get.GetMemberQuery
import com.refinvest.core.member.port.inbound.member.get.GetMemberResult
import com.refinvest.core.member.port.inbound.member.get.GetMemberUseCase
import com.refinvest.core.member.port.outbound.MemberReader
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
