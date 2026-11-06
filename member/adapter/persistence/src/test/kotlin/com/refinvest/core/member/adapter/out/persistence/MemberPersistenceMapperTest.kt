package com.refinvest.core.member.adapter.out.persistence

import com.refinvest.core.member.domain.Member
import com.refinvest.core.member.domain.valueobject.MemberRole
import com.refinvest.core.shared.kernel.member.MemberId
import java.time.Instant
import kotlin.test.*

class MemberPersistenceMapperTest {
    @Test
    fun `restoration preserves database role without applying new member defaults`() {
        val createdAt = Instant.parse("2026-10-01T01:02:03.123456Z")
        MemberRole.entries.forEach { role ->
            val stored = MemberJpaEntity(9007199254740993L, role.name, createdAt)
            val member = MemberPersistenceMapper.toDomain(stored)
            val roundTrip = MemberPersistenceMapper.toEntity(member)
            assertEquals(MemberId(stored.id), member.id)
            assertEquals(role, member.role)
            assertEquals(createdAt, member.createdAt)
            assertEquals(listOf(stored.id, stored.role, stored.createdAt),
                listOf(roundTrip.id, roundTrip.role, roundTrip.createdAt))
        }
        assertEquals(MemberRole.MEMBER, Member.create(MemberId(1), createdAt).role)
    }

    @Test
    fun `unknown persisted role fails instead of silently granting a default role`() {
        assertFailsWith<IllegalArgumentException> {
            MemberPersistenceMapper.toDomain(MemberJpaEntity(1, "OWNER", Instant.EPOCH))
        }
    }
}
