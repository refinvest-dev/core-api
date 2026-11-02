package com.refinvest.core.auth.application.logout

import com.refinvest.core.auth.domain.RefreshSession
import com.refinvest.core.auth.application.support.RecordingTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import com.refinvest.core.auth.domain.valueobject.RefreshSessionRotation
import com.refinvest.core.auth.port.inbound.logout.LogoutCommand
import com.refinvest.core.auth.port.outbound.persistence.RefreshFamilyRevocation
import com.refinvest.core.auth.port.outbound.persistence.RefreshSessionStore
import com.refinvest.core.auth.port.outbound.token.RefreshTokenClaims
import com.refinvest.core.auth.port.outbound.token.RefreshTokenParser
import com.refinvest.core.shared.kernel.member.MemberId
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogoutServiceTest {
    @Test
    fun `missing or unverifiable credential leaves all families unchanged`() {
        val store = RevocationStore()
        val transactions = RecordingTransactionManager()
        val service = LogoutService(store, clock, RefreshTokenParser { null }, TransactionTemplate(transactions))
        listOf(null, "", "malformed", "access-token").forEach { service.execute(LogoutCommand(it)) }
        assertTrue(store.revocations.isEmpty())
        assertEquals(0, transactions.begins)
    }

    @Test
    fun `verified credential revokes exactly its family at the application clock`() {
        val family = UUID.randomUUID()
        val store = RevocationStore()
        val service = LogoutService(store, clock, RefreshTokenParser { raw ->
            if (raw == "verified-refresh") RefreshTokenClaims(UUID.randomUUID(), MemberId(1), family, "fingerprint") else null
        }, TransactionTemplate(RecordingTransactionManager()))
        service.execute(LogoutCommand("verified-refresh"))
        assertEquals(listOf(RefreshFamilyRevocation(family, clock.instant())), store.revocations)
    }

    private class RevocationStore : RefreshSessionStore {
        val revocations = mutableListOf<RefreshFamilyRevocation>()
        override fun findByJti(jti: UUID): RefreshSession? = error("Logout must not load a session")
        override fun save(session: RefreshSession) = error("Logout must not create a session")
        override fun rotate(rotation: RefreshSessionRotation): Boolean = error("Logout must not rotate a session")
        override fun revokeFamily(revocation: RefreshFamilyRevocation) { revocations += revocation }
    }

    private val clock = Clock.fixed(Instant.parse("2026-11-03T00:00:00Z"), ZoneOffset.UTC)
}
