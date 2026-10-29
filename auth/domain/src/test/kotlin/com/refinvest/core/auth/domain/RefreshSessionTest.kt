package com.refinvest.core.auth.domain

import com.refinvest.core.auth.domain.valueobject.RefreshSessionId
import com.refinvest.core.shared.kernel.member.MemberId
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class RefreshSessionTest {
    @Test
    fun `active interval and credential checks include member family and fingerprint`() {
        val session = session()
        assertEquals(RefreshSessionId(UUID(0, 1)), session.id)
        assertTrue(session.isActive(ISSUED))
        assertFalse(session.isActive(ISSUED.minusNanos(1)))
        assertFalse(session.isActive(EXPIRES))
        assertTrue(session.accepts(MemberId(1), FAMILY, "fingerprint-1", NOW))
        assertFalse(session.accepts(MemberId(2), FAMILY, "fingerprint-1", NOW))
        assertFalse(session.accepts(MemberId(1), UUID(0, 99), "fingerprint-1", NOW))
        assertFalse(session.accepts(MemberId(1), FAMILY, "another-credential", NOW))
    }

    @Test
    fun `rotation returns validated states without changing the original aggregate`() {
        val current = session()
        val next = session(2)
        val rotation = current.rotate(next, NOW)
        assertSame(current, rotation.expected)
        assertSame(next, rotation.replacement)
        assertEquals(next.jti, rotation.rotated.replacedByJti)
        assertEquals(NOW, rotation.rotated.revokedAt)
        assertFalse(rotation.rotated.isActive(NOW))
        assertTrue(current.isActive(NOW))
        assertTrue(next.isActive(NOW))
        assertEquals(current.id, rotation.rotated.id)
        assertEquals(current.familyId, rotation.rotated.familyId)
        assertEquals(current.tokenFingerprint, rotation.rotated.tokenFingerprint)
    }

    @Test
    fun `rotation rejects replay expiry and incompatible replacements`() {
        val current = session()
        assertFailsWith<IllegalStateException> { current.revoke(NOW).rotate(session(2), NOW) }
        assertFailsWith<IllegalStateException> { current.rotate(session(2), EXPIRES) }
        listOf(current, session(2, memberId = MemberId(2)), session(2, family = UUID(0, 99)),
            session(2).revoke(NOW)).forEach { replacement ->
            assertFailsWith<IllegalArgumentException> { current.rotate(replacement, NOW) }
        }
    }

    @Test
    fun `revocation preserves the first timestamp and rotation lineage even for expired credentials`() {
        val revoked = session().rotate(session(2), NOW).rotated
        assertSame(revoked, revoked.revoke(NOW.plusSeconds(1)))
        assertEquals(UUID(0, 2), revoked.replacedByJti)
        assertFalse(session().revoke(EXPIRES.plusSeconds(1)).isActive(NOW))
    }

    @Test
    fun `restoration rejects impossible rotation and invalid lifetime`() {
        assertFailsWith<IllegalArgumentException> { restore(null, UUID(0, 2)) }
        assertFailsWith<IllegalArgumentException> { restore(NOW, UUID(0, 1)) }
        assertFailsWith<IllegalArgumentException> {
            RefreshSession.create(UUID(0, 1), MemberId(1), FAMILY, "fingerprint", ISSUED, ISSUED)
        }
        assertFailsWith<IllegalArgumentException> {
            RefreshSession.create(UUID(0, 1), MemberId(1), FAMILY, " ", ISSUED, EXPIRES)
        }
    }

    @Test
    fun `restoration preserves terminal states rather than creating an active session`() {
        val restored = restore(NOW, UUID(0, 2))
        assertFalse(restored.isActive(NOW))
        assertEquals(UUID(0, 2), restored.replacedByJti)
        assertTrue(restored.hasSameStateAs(session().rotate(session(2), NOW).rotated))
        assertFalse(restored.hasSameStateAs(session()))
    }

    private fun restore(revokedAt: Instant?, replacedBy: UUID?) = RefreshSession.restore(
        UUID(0, 1), MemberId(1), FAMILY, "fingerprint-1", ISSUED, EXPIRES, revokedAt, replacedBy)
    private fun session(id: Long = 1, memberId: MemberId = MemberId(1), family: UUID = FAMILY) =
        RefreshSession.create(UUID(0, id), memberId, family, "fingerprint-$id", ISSUED, EXPIRES)

    companion object {
        private val ISSUED = Instant.parse("2026-10-01T00:00:00Z")
        private val NOW = ISSUED.plusSeconds(60)
        private val EXPIRES = ISSUED.plusSeconds(3600)
        private val FAMILY = UUID(0, 10)
    }
}
