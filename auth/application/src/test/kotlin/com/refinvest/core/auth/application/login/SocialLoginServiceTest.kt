package com.refinvest.core.auth.application.login

import com.refinvest.core.auth.domain.RefreshSession
import com.refinvest.core.auth.domain.valueobject.RefreshSessionRotation
import com.refinvest.core.auth.domain.valueobject.SocialIdentityId
import com.refinvest.core.auth.port.outbound.persistence.RefreshFamilyRevocation
import com.refinvest.core.auth.domain.SocialIdentity
import com.refinvest.core.auth.domain.valueobject.SocialProvider
import com.refinvest.core.auth.port.inbound.login.SocialLoginCommand
import com.refinvest.core.auth.port.outbound.token.AuthenticationTokenIssuer
import com.refinvest.core.auth.port.outbound.token.IssuedAuthenticationTokens
import com.refinvest.core.auth.port.outbound.persistence.RefreshSessionStore
import com.refinvest.core.auth.port.outbound.persistence.SocialIdentityStore
import com.refinvest.core.member.domain.valueobject.MemberRole
import com.refinvest.core.member.port.inbound.create.CreateMemberCommand
import com.refinvest.core.member.port.inbound.create.CreateMemberResult
import com.refinvest.core.member.port.inbound.create.CreateMemberUseCase
import com.refinvest.core.member.port.inbound.get.GetMemberQuery
import com.refinvest.core.member.port.inbound.get.GetMemberResult
import com.refinvest.core.member.port.inbound.get.GetMemberUseCase
import com.refinvest.core.shared.kernel.member.MemberId
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SocialLoginServiceTest {
    @Test
    fun `first login creates a member social identity and refresh session`() {
        val fixture = Fixture()

        val result = fixture.service.execute(SocialLoginCommand(SocialProvider.KAKAO, "kakao-subject"))

        assertEquals(MemberId(1), result.memberId)
        assertEquals(1, fixture.members.createCount)
        assertEquals(
            MemberId(1),
            fixture.identities.findById(SocialIdentityId(SocialProvider.KAKAO, "kakao-subject"))?.memberId,
        )
        assertEquals(1, fixture.refreshSessions.saved.size)
        assertEquals(MemberId(1), fixture.refreshSessions.saved.single().memberId)
        assertEquals("MEMBER", fixture.tokenIssuer.issued.single().role)
    }

    @Test
    fun `repeated login resolves the same member without creating another identity`() {
        val fixture = Fixture()
        val command = SocialLoginCommand(SocialProvider.GOOGLE, "google-subject")

        val first = fixture.service.execute(command)
        val second = fixture.service.execute(command)

        assertEquals(first.memberId, second.memberId)
        assertEquals(1, fixture.members.createCount)
        assertEquals(1, fixture.identities.saved.size)
        assertEquals(2, fixture.refreshSessions.saved.size)
    }

    @Test
    fun `the same provider subject from different providers remains a distinct identity`() {
        val fixture = Fixture()

        val kakao = fixture.service.execute(SocialLoginCommand(SocialProvider.KAKAO, "same-subject"))
        val naver = fixture.service.execute(SocialLoginCommand(SocialProvider.NAVER, "same-subject"))

        assertNotEquals(kakao.memberId, naver.memberId)
        assertEquals(2, fixture.members.createCount)
        assertEquals(2, fixture.identities.saved.size)
    }

    @Test
    fun `provider input cannot choose the member role`() {
        val fixture = Fixture()

        fixture.service.execute(SocialLoginCommand(SocialProvider.GOOGLE, "provider-controlled-subject"))

        assertTrue(fixture.tokenIssuer.issued.all { it.role == MemberRole.MEMBER.name })
    }

    private class Fixture {
        val identities = InMemorySocialIdentityStore()
        val refreshSessions = RecordingRefreshSessionStore()
        val members = InMemoryMemberUseCases()
        val tokenIssuer = RecordingAuthenticationTokenIssuer()
        val service = SocialLoginService(identities, refreshSessions, tokenIssuer, members, members)
    }

    private class InMemorySocialIdentityStore : SocialIdentityStore {
        val saved = mutableListOf<SocialIdentity>()
        private val memberIds = mutableMapOf<Pair<SocialProvider, String>, MemberId>()

        override fun findById(id: SocialIdentityId): SocialIdentity? =
            memberIds[id.provider to id.providerSubject]?.let { SocialIdentity.restore(id, it) }

        override fun save(identity: SocialIdentity) {
            val key = identity.provider to identity.providerSubject
            check(memberIds.putIfAbsent(key, identity.memberId) == null) { "Duplicate social identity" }
            saved += identity
        }
    }

    private class RecordingRefreshSessionStore : RefreshSessionStore {
        val saved = mutableListOf<RefreshSession>()

        override fun findByJti(jti: UUID): RefreshSession? = saved.find { it.jti == jti }

        override fun save(session: RefreshSession) {
            saved += session
        }

        override fun rotate(rotation: RefreshSessionRotation): Boolean = false

        override fun revokeFamily(revocation: RefreshFamilyRevocation) = Unit
    }

    private class InMemoryMemberUseCases : CreateMemberUseCase, GetMemberUseCase {
        var createCount = 0
        private val members = mutableMapOf<MemberId, GetMemberResult>()

        override fun execute(command: CreateMemberCommand): CreateMemberResult {
            val memberId = MemberId((++createCount).toLong())
            val member = GetMemberResult(memberId, MemberRole.MEMBER)
            members[memberId] = member
            return CreateMemberResult(memberId, member.role)
        }

        override fun execute(query: GetMemberQuery): GetMemberResult? = members[query.memberId]
    }

    private class RecordingAuthenticationTokenIssuer : AuthenticationTokenIssuer {
        data class Issue(val memberId: MemberId, val role: String, val refreshFamilyId: UUID?)

        val issued = mutableListOf<Issue>()

        override fun issue(memberId: MemberId, role: String, refreshFamilyId: UUID?): IssuedAuthenticationTokens {
            issued += Issue(memberId, role, refreshFamilyId)
            val now = Instant.parse("2026-01-01T00:00:00Z")
            return IssuedAuthenticationTokens(
                issuedAt = now,
                accessToken = "access-${issued.size}",
                accessTokenExpiresAt = now.plusSeconds(900),
                refreshToken = "refresh-${issued.size}",
                refreshJti = UUID.nameUUIDFromBytes("refresh-${issued.size}".toByteArray()),
                refreshFamilyId = UUID.nameUUIDFromBytes("family-${issued.size}".toByteArray()),
                refreshTokenFingerprint = "fingerprint-${issued.size}",
                refreshTokenExpiresAt = now.plusSeconds(86_400),
            )
        }
    }
}
