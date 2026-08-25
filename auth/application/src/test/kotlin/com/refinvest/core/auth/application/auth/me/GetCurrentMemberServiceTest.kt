package com.refinvest.core.auth.application.auth.me

import com.refinvest.core.auth.port.outbound.CurrentMemberIdProvider
import com.refinvest.core.member.domain.MemberRole
import com.refinvest.core.member.port.inbound.get.GetMemberQuery
import com.refinvest.core.member.port.inbound.get.GetMemberResult
import com.refinvest.core.member.port.inbound.get.GetMemberUseCase
import com.refinvest.core.shared.kernel.member.MemberId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GetCurrentMemberServiceTest {
    @Test
    fun `returns the current member using the persisted role`() {
        val memberId = MemberId(101)
        val service = GetCurrentMemberService(
            CurrentMemberIdProvider { memberId },
            StubGetMemberUseCase(GetMemberResult(memberId, MemberRole.ADMIN)),
        )

        val result = service.execute()

        assertEquals(memberId, result?.memberId)
        assertEquals("ADMIN", result?.role)
    }

    @Test
    fun `returns null when the authenticated member no longer exists`() {
        val service = GetCurrentMemberService(
            CurrentMemberIdProvider { MemberId(101) },
            StubGetMemberUseCase(null),
        )

        assertNull(service.execute())
    }

    private class StubGetMemberUseCase(
        private val result: GetMemberResult?,
    ) : GetMemberUseCase {
        override fun execute(query: GetMemberQuery): GetMemberResult? = result
    }
}
