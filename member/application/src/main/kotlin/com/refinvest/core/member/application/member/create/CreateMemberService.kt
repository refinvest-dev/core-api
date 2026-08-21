package com.refinvest.core.member.application.member.create

import com.refinvest.core.member.domain.Member
import com.refinvest.core.member.port.inbound.member.create.CreateMemberCommand
import com.refinvest.core.member.port.inbound.member.create.CreateMemberResult
import com.refinvest.core.member.port.inbound.member.create.CreateMemberUseCase
import com.refinvest.core.member.port.outbound.MemberIdGenerator
import com.refinvest.core.member.port.outbound.MemberStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
open class CreateMemberService(
    private val memberStore: MemberStore,
    private val memberIdGenerator: MemberIdGenerator,
    private val clock: Clock,
) : CreateMemberUseCase {
    @Transactional
    override fun execute(command: CreateMemberCommand): CreateMemberResult {
        val member = Member.create(memberIdGenerator.next(), clock.instant())
        memberStore.save(member)
        return CreateMemberResult(member.id, member.role)
    }
}
