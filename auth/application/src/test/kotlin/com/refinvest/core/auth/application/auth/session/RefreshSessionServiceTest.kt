package com.refinvest.core.auth.application.auth.session

import com.refinvest.core.auth.domain.RefreshSession
import com.refinvest.core.auth.port.inbound.auth.session.RefreshSessionCommand
import com.refinvest.core.auth.port.outbound.AuthenticationTokenIssuer
import com.refinvest.core.auth.port.outbound.IssuedAuthenticationTokens
import com.refinvest.core.auth.port.outbound.RefreshSessionStore
import com.refinvest.core.member.domain.MemberRole
import com.refinvest.core.member.port.inbound.member.get.GetMemberQuery
import com.refinvest.core.member.port.inbound.member.get.GetMemberResult
import com.refinvest.core.member.port.inbound.member.get.GetMemberUseCase
import com.refinvest.core.shared.kernel.member.MemberId
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RefreshSessionServiceTest {
    @Test
    fun `valid refresh rotates the session and issues tokens with the current member role`() {
        val fixture = Fixture(role = MemberRole.ADMIN)
        val current = fixture.activeSession()

        val result = fixture.service.execute(current.command())

        assertNotNull(result)
        assertEquals("access-1", result.accessToken)
        assertEquals("ADMIN", fixture.tokenIssuer.issued.single().role)
        assertEquals(current.familyId, fixture.tokenIssuer.issued.single().refreshFamilyId)
        assertFalse(fixture.sessions.get(current.jti).isActive(fixture.now))
        assertEquals(2, fixture.sessions.all().size)
        assertTrue(fixture.sessions.all().single { it.jti != current.jti }.isActive(fixture.now))
    }

    @Test
    fun `replay of a rotated refresh session revokes its active token family`() {
        val fixture = Fixture()
        val current = fixture.activeSession()
        fixture.service.execute(current.command())

        val replayResult = fixture.service.execute(current.command())

        assertNull(replayResult)
        assertEquals(listOf(current.familyId), fixture.sessions.revokedFamilies)
        assertTrue(fixture.sessions.all().filter { it.familyId == current.familyId }.none { it.isActive(fixture.now) })
    }

    @Test
    fun `expired refresh session is rejected and its family is revoked`() {
        val fixture = Fixture()
        val expired = fixture.activeSession(expiresAt = fixture.now.minusSeconds(1))

        val result = fixture.service.execute(expired.command())

        assertNull(result)
        assertEquals(listOf(expired.familyId), fixture.sessions.revokedFamilies)
        assertTrue(fixture.tokenIssuer.issued.isEmpty())
    }

    @Test
    fun `missing member rejects refresh and revokes its family`() {
        val fixture = Fixture(memberExists = false)
        val current = fixture.activeSession()

        val result = fixture.service.execute(current.command())

        assertNull(result)
        assertEquals(listOf(current.familyId), fixture.sessions.revokedFamilies)
        assertTrue(fixture.tokenIssuer.issued.isEmpty())
    }

    @Test
    fun `concurrent rotation loss revokes the token family`() {
        val fixture = Fixture(rotationSucceeds = false)
        val current = fixture.activeSession()

        val result = fixture.service.execute(current.command())

        assertNull(result)
        assertEquals(listOf(current.familyId), fixture.sessions.revokedFamilies)
    }

    private class Fixture(
        role: MemberRole = MemberRole.MEMBER,
        memberExists: Boolean = true,
        rotationSucceeds: Boolean = true,
    ) {
        val now: Instant = Instant.parse("2026-01-01T00:00:00Z")
        val sessions = InMemoryRefreshSessionStore(rotationSucceeds)
        val members = StubMemberReader(if (memberExists) GetMemberResult(MemberId(1), role) else null)
        val tokenIssuer = RecordingAuthenticationTokenIssuer(now)
        val service = RefreshSessionService(sessions, members, tokenIssuer, Clock.fixed(now, ZoneOffset.UTC))

        fun activeSession(expiresAt: Instant = now.plusSeconds(3600)): RefreshSession = RefreshSession(
            jti = UUID.nameUUIDFromBytes("current-jti".toByteArray()),
            memberId = MemberId(1),
            familyId = UUID.nameUUIDFromBytes("family".toByteArray()),
            tokenFingerprint = "current-fingerprint",
            issuedAt = now.minusSeconds(60),
            expiresAt = expiresAt,
        ).also(sessions::save)
    }

    private class StubMemberReader(private val member: GetMemberResult?) : GetMemberUseCase {
        override fun execute(query: GetMemberQuery): GetMemberResult? = member
    }

    private class InMemoryRefreshSessionStore(private val rotationSucceeds: Boolean) : RefreshSessionStore {
        private val sessions = mutableMapOf<UUID, RefreshSession>()
        val revokedFamilies = mutableListOf<UUID>()

        override fun findByJti(jti: UUID): RefreshSession? = sessions[jti]

        override fun save(session: RefreshSession) {
            sessions[session.jti] = session
        }

        override fun rotate(current: RefreshSession, replacement: RefreshSession, revokedAt: Instant): Boolean {
            if (!rotationSucceeds || sessions[current.jti] != current || !current.isActive(revokedAt)) return false
            sessions[current.jti] = current.copy(revokedAt = revokedAt, replacedByJti = replacement.jti)
            sessions[replacement.jti] = replacement
            return true
        }

        override fun revokeFamily(familyId: UUID, revokedAt: Instant) {
            revokedFamilies += familyId
            sessions.entries.filter { it.value.familyId == familyId }.forEach { (jti, session) ->
                sessions[jti] = session.copy(revokedAt = revokedAt)
            }
        }

        fun get(jti: UUID): RefreshSession = requireNotNull(sessions[jti])

        fun all(): Collection<RefreshSession> = sessions.values
    }

    private class RecordingAuthenticationTokenIssuer(private val now: Instant) : AuthenticationTokenIssuer {
        data class Issue(val memberId: MemberId, val role: String, val refreshFamilyId: UUID?)

        val issued = mutableListOf<Issue>()

        override fun issue(memberId: MemberId, role: String, refreshFamilyId: UUID?): IssuedAuthenticationTokens {
            issued += Issue(memberId, role, refreshFamilyId)
            val index = issued.size
            return IssuedAuthenticationTokens(
                issuedAt = now,
                accessToken = "access-$index",
                accessTokenExpiresAt = now.plusSeconds(900),
                refreshToken = "refresh-$index",
                refreshJti = UUID.nameUUIDFromBytes("replacement-$index".toByteArray()),
                refreshFamilyId = refreshFamilyId ?: UUID.nameUUIDFromBytes("family-$index".toByteArray()),
                refreshTokenFingerprint = "replacement-fingerprint-$index",
                refreshTokenExpiresAt = now.plusSeconds(86_400),
            )
        }
    }

    private fun RefreshSession.command(): RefreshSessionCommand = RefreshSessionCommand(
        jti = jti,
        memberId = memberId,
        familyId = familyId,
        tokenFingerprint = tokenFingerprint,
    )
}
