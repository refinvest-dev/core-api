package com.refinvest.core.auth.application.authority

import com.refinvest.core.auth.port.inbound.authority.CheckMemberAuthorityCommand
import com.refinvest.core.auth.port.inbound.authority.MemberAuthorityUnavailableException
import com.refinvest.core.auth.port.outbound.authority.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*

class CheckMemberAuthorityServiceTest {
    @Test
    fun `invalid signature produces only an inactive receipt without a state read`() {
        var reads = 0
        val service = CheckMemberAuthorityService(DelegationVerifier { _, _, _ -> null },
            MemberAuthorityReader { reads++; error("must not read") }, clock)

        val result = service.execute(command)

        assertFalse(result.active)
        assertNull(result.identity)
        assertEquals(command.checkId, result.checkId)
        assertEquals(64, result.tokenSha256.length)
        assertEquals(0, reads)
        assertEquals("MemberAuthorityResult(redacted)", result.toString())
    }

    @Test
    fun `uses one current snapshot and returns its revision with the verified binding`() {
        var reads = 0
        val service = CheckMemberAuthorityService(DelegationVerifier { _, _, _ -> verified }, MemberAuthorityReader {
            reads++
            assertSame(verified, it.delegation)
            assertEquals(64, it.tokenIdSha256.length)
            MemberAuthoritySnapshot(true, now, 7)
        }, clock)

        val result = service.execute(command)

        assertTrue(result.active)
        assertEquals("9007199254740993", result.identity?.subject)
        assertEquals(7, result.identity?.policyRevision)
        assertEquals(now, result.checkedAt)
        assertEquals(1, reads)
    }

    @Test
    fun `denial drops identity while state failure is unavailable without raw causes`() {
        val verifier = DelegationVerifier { _, _, _ -> verified }
        val denied = CheckMemberAuthorityService(verifier, MemberAuthorityReader { MemberAuthoritySnapshot(false, now, null) }, clock)
        assertNull(denied.execute(command).identity)

        val failed = CheckMemberAuthorityService(verifier, MemberAuthorityReader { error("sensitive database failure") }, clock)
        val failure = assertFailsWith<MemberAuthorityUnavailableException> { failed.execute(command) }
        assertNull(failure.cause)
        assertEquals("Member authority unavailable", failure.message)
    }

    companion object {
        private val now = Instant.ofEpochSecond(1794096000)
        private val clock = Clock.fixed(now, ZoneOffset.UTC)
        private val command = CheckMemberAuthorityCommand("synthetic-test-only", "core-api", "0".repeat(32))
        private val verified = VerifiedAuthorityDelegation(9007199254740993, "core-api", "https://issuer.invalid", "k1", "aud", "scope", "test-jti", now.epochSecond, now.epochSecond, now.epochSecond + 60)
    }
}
