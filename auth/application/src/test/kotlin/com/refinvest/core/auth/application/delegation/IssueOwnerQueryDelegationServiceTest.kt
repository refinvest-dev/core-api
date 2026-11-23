package com.refinvest.core.auth.application.delegation

import com.refinvest.core.auth.port.outbound.delegation.DelegationSigningException
import com.refinvest.core.auth.port.outbound.delegation.OwnerQueryDelegationPayload
import com.refinvest.core.auth.port.outbound.delegation.OwnerQueryDelegationSigner
import com.refinvest.core.auth.port.outbound.member.CurrentMemberIdProvider
import com.refinvest.core.shared.kernel.member.MemberId
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class IssueOwnerQueryDelegationServiceTest {
    private val now = Instant.parse("2026-11-23T00:00:00.987654321Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `uses the authenticated member and fixed second precision lifetime`() {
        var captured: OwnerQueryDelegationPayload? = null
        val service = IssueOwnerQueryDelegationService(
            CurrentMemberIdProvider { MemberId(9007199254740993) },
            OwnerQueryDelegationSigner { payload ->
                captured = payload
                "test-only-compact-token"
            },
            clock,
            { "test-only-jti" },
        )

        val result = service.execute()

        assertEquals(MemberId(9007199254740993), captured?.memberId)
        assertEquals("test-only-jti", captured?.tokenId)
        assertEquals(Instant.parse("2026-11-23T00:00:00Z"), result.issuedAt)
        assertEquals(result.issuedAt, captured?.issuedAt)
        assertEquals(result.issuedAt.plusSeconds(60), result.expiresAt)
        assertEquals("test-only-compact-token", result.compactToken)
        assertEquals("OwnerQueryDelegationResult(redacted)", result.toString())
        assertEquals("OwnerQueryDelegationPayload(redacted)", captured.toString())
    }

    @Test
    fun `every invocation creates a fresh opaque jti`() {
        val ids = mutableListOf<String>()
        val service = IssueOwnerQueryDelegationService(
            CurrentMemberIdProvider { MemberId(7) },
            OwnerQueryDelegationSigner {
                ids.add(it.tokenId)
                "test-only-token"
            },
            clock,
        )

        service.execute()
        service.execute()

        assertEquals(2, ids.size)
        assertNotEquals(ids[0], ids[1])
        assertTrue(ids.all { UUID.fromString(it).toString() == it })
    }

    @Test
    fun `principal failure never invokes signer`() {
        var calls = 0
        val failure = IllegalStateException("No authenticated principal")
        val service = IssueOwnerQueryDelegationService(
            CurrentMemberIdProvider { throw failure },
            OwnerQueryDelegationSigner {
                calls++
                "unused"
            },
            clock,
        )

        assertSame(failure, assertFailsWith<IllegalStateException> { service.execute() })
        assertEquals(0, calls)
    }

    @Test
    fun `signing failure returns no receipt and preserves its safe classification`() {
        val failure = DelegationSigningException(DelegationSigningException.Reason.SIGNING_UNAVAILABLE)
        val service = IssueOwnerQueryDelegationService(
            CurrentMemberIdProvider { MemberId(7) },
            OwnerQueryDelegationSigner { throw failure },
            clock,
        )

        assertSame(failure, assertFailsWith<DelegationSigningException> { service.execute() })
    }
}
