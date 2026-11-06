package com.refinvest.core.auth.application.session

import com.refinvest.core.auth.domain.valueobject.RefreshSessionRotation
import com.refinvest.core.auth.port.outbound.persistence.RefreshFamilyRevocation
import com.refinvest.core.auth.domain.RefreshSession
import com.refinvest.core.auth.port.inbound.session.RefreshSessionCommand
import com.refinvest.core.auth.port.outbound.token.AuthenticationTokenIssuer
import com.refinvest.core.auth.port.outbound.token.IssuedAuthenticationTokens
import com.refinvest.core.auth.port.outbound.token.RefreshTokenClaims
import com.refinvest.core.auth.port.outbound.token.RefreshTokenParser
import com.refinvest.core.auth.application.support.RecordingTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import com.refinvest.core.auth.port.outbound.persistence.RefreshSessionStore
import com.refinvest.core.member.port.inbound.MemberRole
import com.refinvest.core.member.port.inbound.get.GetMemberQuery
import com.refinvest.core.member.port.inbound.get.GetMemberResult
import com.refinvest.core.member.port.inbound.get.GetMemberUseCase
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
    fun `missing and unverifiable credentials never read sessions or revoke a family`() {
        val fixture = Fixture()
        val current = fixture.activeSession()
        for (raw in listOf(null, "", "malformed", "access-token", "invalid-signature")) {
            assertNull(fixture.service.execute(RefreshSessionCommand(raw)))
        }
        assertEquals(0, fixture.sessions.reads)
        assertEquals(0, fixture.transactions.begins)
        assertTrue(fixture.sessions.revokedFamilies.isEmpty())
        assertTrue(fixture.tokenIssuer.issued.isEmpty())
        assertTrue(fixture.sessions.get(current.jti).isActive(fixture.now))
    }

    @Test
    fun `verified credential without a stored session revokes only its claimed family`() {
        val fixture = Fixture()
        val current = fixture.activeSession()
        val raw = fixture.command(current).rawRefreshToken!!
        fixture.claimsByToken[raw] = fixture.claimsByToken.getValue(raw).copy(jti = UUID.randomUUID())
        assertNull(fixture.service.execute(RefreshSessionCommand(raw)))
        assertEquals(listOf(current.familyId), fixture.sessions.revokedFamilies)
        assertTrue(fixture.tokenIssuer.issued.isEmpty())
    }

    @Test
    fun `credential commands redact their diagnostic text`() {
        val secret = "refresh-credential-must-not-appear"
        assertFalse(RefreshSessionCommand(secret).toString().contains(secret))
        assertFalse(com.refinvest.core.auth.port.inbound.logout.LogoutCommand(secret).toString().contains(secret))
    }

    @Test
    fun `valid refresh rotates the session and issues tokens with the current member role`() {
        val fixture = Fixture(role = MemberRole.ADMIN)
        val current = fixture.activeSession()

        val result = fixture.service.execute(fixture.command(current))

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
        fixture.service.execute(fixture.command(current))

        val replayResult = fixture.service.execute(fixture.command(current))

        assertNull(replayResult)
        assertEquals(listOf(current.familyId), fixture.sessions.revokedFamilies)
        assertTrue(fixture.sessions.all().filter { it.familyId == current.familyId }.none { it.isActive(fixture.now) })
    }

    @Test
    fun `expired refresh session is rejected and its family is revoked`() {
        val fixture = Fixture()
        val expired = fixture.activeSession(expiresAt = fixture.now.minusSeconds(1))

        val result = fixture.service.execute(fixture.command(expired))

        assertNull(result)
        assertEquals(listOf(expired.familyId), fixture.sessions.revokedFamilies)
        assertTrue(fixture.tokenIssuer.issued.isEmpty())
    }

    @Test
    fun `missing member rejects refresh and revokes its family`() {
        val fixture = Fixture(memberExists = false)
        val current = fixture.activeSession()

        val result = fixture.service.execute(fixture.command(current))

        assertNull(result)
        assertEquals(listOf(current.familyId), fixture.sessions.revokedFamilies)
        assertTrue(fixture.tokenIssuer.issued.isEmpty())
    }

    @Test
    fun `concurrent rotation loss revokes the token family`() {
        val fixture = Fixture(rotationSucceeds = false)
        val current = fixture.activeSession()

        val result = fixture.service.execute(fixture.command(current))

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
        val claimsByToken = mutableMapOf<String, RefreshTokenClaims>()
        val transactions = RecordingTransactionManager()
        val service = RefreshSessionService(sessions, members, tokenIssuer, Clock.fixed(now, ZoneOffset.UTC),
            RefreshTokenParser { claimsByToken[it] }, TransactionTemplate(transactions))

        fun command(session: RefreshSession): RefreshSessionCommand {
            val raw = "refresh-${session.jti}"
            claimsByToken[raw] = RefreshTokenClaims(session.jti, session.memberId, session.familyId, session.tokenFingerprint)
            return RefreshSessionCommand(raw)
        }

        fun activeSession(expiresAt: Instant = now.plusSeconds(3600)): RefreshSession = RefreshSession.create(
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
        var reads = 0

        override fun findByJti(jti: UUID): RefreshSession? { reads++; return sessions[jti] }

        override fun save(session: RefreshSession) {
            sessions[session.jti] = session
        }

        override fun rotate(rotation: RefreshSessionRotation): Boolean {
            if (!rotationSucceeds || sessions[rotation.expected.jti]?.hasSameStateAs(rotation.expected) != true) return false
            sessions[rotation.expected.jti] = rotation.rotated
            sessions[rotation.replacement.jti] = rotation.replacement
            return true
        }

        override fun revokeFamily(revocation: RefreshFamilyRevocation) {
            revokedFamilies += revocation.familyId
            sessions.entries.filter { it.value.familyId == revocation.familyId }.forEach { (jti, session) ->
                sessions[jti] = session.revoke(revocation.revokedAt)
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

}
