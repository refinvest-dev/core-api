package com.refinvest.core.auth.adapter.out.persistence

import com.refinvest.core.auth.domain.RefreshSession
import com.refinvest.core.auth.domain.valueobject.SocialProvider
import com.refinvest.core.shared.kernel.member.MemberId
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class AuthPersistenceMapperTest {
    @Test
    fun `all session lifecycle states survive mapping without reactivation or renewed expiry`() {
        val current = session(1)
        val rotated = current.rotate(session(2), NOW).rotated
        listOf(current, current.revoke(NOW), rotated).forEach { original ->
            val restored = RefreshSessionPersistenceMapper.toDomain(RefreshSessionPersistenceMapper.toEntity(original))
            assertTrue(original.hasSameStateAs(restored))
            assertEquals(original.isActive(NOW), restored.isActive(NOW))
            if (restored.isActive(NOW)) assertEquals(UUID(0, 3), restored.rotate(session(3), NOW).rotated.replacedByJti)
        }
    }

    @Test
    fun `invalid stored lineage is rejected on restoration`() {
        val invalid = RefreshSessionPersistenceMapper.toEntity(session(1)).apply { replacedByJti = UUID(0, 2) }
        assertFailsWith<IllegalArgumentException> { RefreshSessionPersistenceMapper.toDomain(invalid) }
    }

    @Test
    fun `social mapping restores natural aggregate key while database surrogate stays persistence owned`() {
        SocialProvider.entries.forEach { provider ->
            val stored = SocialIdentityJpaEntity(42, provider.name, "Exact:Subject", 9007199254740993L)
            val domain = SocialIdentityPersistenceMapper.toDomain(stored)
            val mapped = SocialIdentityPersistenceMapper.toEntity(domain)
            assertEquals(provider, domain.id.provider)
            assertEquals(stored.providerSubject, domain.id.providerSubject)
            assertEquals(MemberId(stored.memberId), domain.memberId)
            assertEquals(stored.provider, mapped.provider)
            assertEquals(stored.providerSubject, mapped.providerSubject)
            assertEquals(stored.memberId, mapped.memberId)
        }
    }

    @Test
    fun `unknown stored provider cannot silently become a different identity`() {
        assertFailsWith<IllegalArgumentException> {
            SocialIdentityPersistenceMapper.toDomain(SocialIdentityJpaEntity(provider = "OTHER", providerSubject = "subject", memberId = 1))
        }
    }

    private fun session(id: Long) = RefreshSession.create(UUID(0, id), MemberId(9007199254740993L), UUID(0, 10),
        "fingerprint-$id", NOW.minusSeconds(60), NOW.plusSeconds(3600))
    companion object { private val NOW = Instant.parse("2026-10-01T00:00:00.123456Z") }
}
