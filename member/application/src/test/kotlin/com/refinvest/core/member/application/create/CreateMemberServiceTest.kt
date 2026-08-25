package com.refinvest.core.member.application.create

import com.refinvest.core.member.domain.Member
import com.refinvest.core.member.port.inbound.create.CreateMemberCommand
import com.refinvest.core.member.port.outbound.id.MemberIdGenerator
import com.refinvest.core.member.port.outbound.persistence.MemberStore
import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.subscription.domain.valueobject.SubscriptionTier
import com.refinvest.core.subscription.port.inbound.create.CreateSubscriptionCommand
import com.refinvest.core.subscription.port.inbound.create.CreateSubscriptionResult
import com.refinvest.core.subscription.port.inbound.create.CreateSubscriptionUseCase
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class CreateMemberServiceTest {
    @Test
    fun `creates a default free subscription for a new member`() {
        val memberId = MemberId(101)
        val memberStore = RecordingMemberStore()
        val subscriptionUseCase = RecordingCreateSubscriptionUseCase()
        val service = CreateMemberService(
            memberStore = memberStore,
            memberIdGenerator = MemberIdGenerator { memberId },
            createSubscriptionUseCase = subscriptionUseCase,
            clock = Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC),
        )

        val result = service.execute(CreateMemberCommand)

        assertEquals(memberId, result.memberId)
        assertEquals(memberId, memberStore.saved.single().id)
        assertEquals(memberId, subscriptionUseCase.commands.single().memberId)
    }

    private class RecordingMemberStore : MemberStore {
        val saved = mutableListOf<Member>()

        override fun save(member: Member) {
            saved += member
        }
    }

    private class RecordingCreateSubscriptionUseCase : CreateSubscriptionUseCase {
        val commands = mutableListOf<CreateSubscriptionCommand>()

        override fun execute(command: CreateSubscriptionCommand): CreateSubscriptionResult {
            commands += command
            return CreateSubscriptionResult(command.memberId, SubscriptionTier.FREE)
        }
    }
}
