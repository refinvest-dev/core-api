package com.refinvest.core.auth.application.me

import com.refinvest.core.auth.port.inbound.me.GetCurrentMemberResult
import com.refinvest.core.auth.port.inbound.me.GetCurrentMemberUseCase
import com.refinvest.core.auth.port.outbound.member.CurrentMemberIdProvider
import com.refinvest.core.member.port.inbound.get.GetMemberQuery
import com.refinvest.core.member.port.inbound.get.GetMemberUseCase
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
open class GetCurrentMemberService(
    private val currentMemberIdProvider: CurrentMemberIdProvider,
    private val getMemberUseCase: GetMemberUseCase,
) : GetCurrentMemberUseCase {
    @Transactional(readOnly = true)
    override fun execute(): GetCurrentMemberResult? = getMemberUseCase
        .execute(GetMemberQuery(currentMemberIdProvider.currentMemberId()))
        ?.let { member -> GetCurrentMemberResult(member.memberId, member.role.name) }
}
