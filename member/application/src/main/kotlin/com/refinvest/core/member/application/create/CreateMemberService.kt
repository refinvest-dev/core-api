package com.refinvest.core.member.application.create

import com.refinvest.core.member.domain.Member
import com.refinvest.core.member.port.inbound.create.CreateMemberCommand
import com.refinvest.core.member.port.inbound.create.CreateMemberResult
import com.refinvest.core.member.port.inbound.create.CreateMemberUseCase
import com.refinvest.core.member.port.outbound.id.MemberIdGenerator
import com.refinvest.core.member.port.outbound.persistence.MemberStore
import com.refinvest.core.subscription.port.inbound.subscription.create.CreateSubscriptionCommand
import com.refinvest.core.subscription.port.inbound.subscription.create.CreateSubscriptionUseCase
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
open class CreateMemberService(
    private val memberStore: MemberStore,
    private val memberIdGenerator: MemberIdGenerator,
    private val createSubscriptionUseCase: CreateSubscriptionUseCase,
    private val clock: Clock,
) : CreateMemberUseCase {
    @Transactional
    override fun execute(command: CreateMemberCommand): CreateMemberResult {
        val member = Member.create(memberIdGenerator.next(), clock.instant())
        memberStore.save(member)
        createSubscriptionUseCase.execute(CreateSubscriptionCommand(member.id))
        return CreateMemberResult(member.id, member.role)
    }
}
