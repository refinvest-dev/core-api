package com.refinvest.core.member.application.get

import com.refinvest.core.member.domain.Member
import com.refinvest.core.member.domain.valueobject.MemberRole
import com.refinvest.core.member.port.inbound.get.GetMemberQuery
import com.refinvest.core.shared.kernel.member.MemberId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GetMemberServiceTest {
    @Test
    fun bothMemberAndAdminAuthoritiesSurviveThePortBoundary() {
        MemberRole.entries.forEach { role ->
            val member = Member.restore(MemberId(9007199254740993L), role, Instant.EPOCH)
            val service = GetMemberService { id -> member.takeIf { it.id == id } }
            val result = service.execute(GetMemberQuery(member.id))!!
            assertEquals(member.id, result.memberId)
            assertEquals(role.name, result.role.name)
        }
    }

    @Test
    fun missingMemberDoesNotBecomeAnAuthenticatedDefaultMember() {
        assertNull(GetMemberService { null }.execute(GetMemberQuery(MemberId(1))))
    }
}
