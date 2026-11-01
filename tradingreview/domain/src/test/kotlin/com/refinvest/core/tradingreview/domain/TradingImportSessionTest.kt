package com.refinvest.core.tradingreview.domain

import com.refinvest.core.tradingreview.domain.valueobject.NormalizationRunId
import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.importsession.TradingSourceArtifact
import com.refinvest.core.tradingreview.domain.valueobject.TradingAccountId
import com.refinvest.core.tradingreview.domain.valueobject.TradingArtifactRetentionStatus
import com.refinvest.core.tradingreview.domain.valueobject.TradingArtifactRole
import com.refinvest.core.tradingreview.domain.valueobject.TradingBookId
import com.refinvest.core.tradingreview.domain.valueobject.TradingDataPolicy
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportFailure
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportSessionId
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportStatus
import com.refinvest.core.tradingreview.domain.valueobject.TradingPositionMode
import com.refinvest.core.tradingreview.domain.valueobject.TradingProductFamily
import com.refinvest.core.tradingreview.domain.valueobject.TradingSettlementAsset
import com.refinvest.core.tradingreview.domain.valueobject.TradingSourceArtifactId
import com.refinvest.core.tradingreview.domain.valueobject.TradingVenue
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TradingImportSessionTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val account = TradingAccount.create(TradingAccountId(1), MemberId(7), TradingVenue.BINANCE, "A", now)
    private val book = TradingBook.create(TradingBookId(2), account, "B", TradingProductFamily.LINEAR_PERPETUAL,
        TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "Asia/Seoul", now)
    private val session = TradingImportSession.create(TradingImportSessionId(3), book, "UTC", "Asia/Seoul",
        TradingPositionMode.ONE_WAY, TradingDataPolicy(), "exact", now)

    @Test
    fun sessionPinsOwnerBookTimezoneAndMode() {
        assertEquals(book.owner, session.owner)
        assertEquals(book.id, session.bookId)
        assertEquals("UTC", session.sourceTimezone)
        assertEquals("Asia/Seoul", session.reviewTimezone)
        assertEquals(TradingPositionMode.ONE_WAY, session.declaredPositionMode)
        assertEquals("TRADING_DATA_POLICY_V1", session.dataPolicyVersion)
        assertFailsWith<IllegalArgumentException> {
            TradingImportSession.create(TradingImportSessionId(4), book, "+09:00", "Asia/Seoul",
                TradingPositionMode.ONE_WAY, TradingDataPolicy(), "exact", now)
        }
    }

    @Test
    fun readinessUsesActiveRolesAndDeletionGeneration() {
        assertFalse(session.canValidate(emptyList()))
        assertEquals(2, session.missingArtifactRoles(emptyList()).size)
        val trade = artifact(4, TradingArtifactRole.TRADE_HISTORY)
        val position = artifact(5, TradingArtifactRole.POSITION_HISTORY)
        assertFalse(session.canValidate(listOf(trade)))
        assertTrue(session.canValidate(listOf(trade, position)))
        assertFalse(session.canValidate(listOf(trade, position.requestDeletion())))
        val deleting = session.requestDeletion(now.plusSeconds(1))
        assertEquals(1, deleting.deletionGeneration)
        assertFalse(deleting.canUpload())
        assertFalse(deleting.canValidate(listOf(trade, position)))
        assertFailsWith<IllegalArgumentException> { deleting.requestDeletion(now.plusSeconds(2)) }
    }

    private fun artifact(id: Long, role: TradingArtifactRole) = TradingSourceArtifact.restore(
        TradingSourceArtifactId(id), session.id, role, null, "text/csv", 1, "0".repeat(64), "AUTO",
        "objects/00000000-0000-0000-0000-000000000001", byteArrayOf(1), "test",
        TradingArtifactRetentionStatus.ACTIVE, now, now.plusSeconds(86400), null, null, 0)

    @Test
    fun uploadAndRawDeletionAreRootActionsAndPreservePins() {
        val received = upload(session, 4, TradingArtifactRole.TRADE_HISTORY)
        assertTrue(session.artifacts.isEmpty())
        assertEquals(1, received.artifacts.size)
        assertFailsWith<IllegalArgumentException> { upload(received, 5, TradingArtifactRole.TRADE_HISTORY) }
        val validating = TradingImportSession.restore(received.id, received.bookId, received.owner, received.sourceTimezone,
            received.reviewTimezone, received.declaredPositionMode, received.dataPolicyVersion, received.retentionPolicy,
            received.maskingPolicyVersion, received.evidenceRetention, received.deletionPolicyVersion, received.versionSetJson,
            TradingImportStatus.VALIDATING, 0, false, now, now, 0, now,
            NormalizationRunId("00000000-0000-0000-0000-000000000001"), artifacts = received.artifacts)
        assertFailsWith<IllegalArgumentException> { upload(validating, 5, TradingArtifactRole.POSITION_HISTORY) }
        val deleted = validating.requestArtifactDeletion(TradingSourceArtifactId(4), now.plusSeconds(1))
        assertEquals(TradingImportStatus.PROCESSING_FAILED, deleted.status)
        assertEquals("SOURCE_ARTIFACT_DELETED", deleted.failure?.code)
        assertEquals(1L, deleted.deletionGeneration)
        assertFalse(deleted.deletionPending)
        assertEquals(TradingArtifactRetentionStatus.DELETION_PENDING, deleted.artifacts.single().retentionStatus)
        assertEquals(validating.versionSetJson, deleted.versionSetJson)
        assertEquals(validating.activeNormalizationRunId, deleted.activeNormalizationRunId)
        assertEquals(null, deleted.failValidation("00000000-0000-0000-0000-000000000001", 0, TradingImportFailure("LATE", true, "RETRY", now), now))
        assertFailsWith<IllegalArgumentException> { received.requestArtifactDeletion(TradingSourceArtifactId(999), now) }
    }

    @Test
    fun readinessRejectsForeignArtifactsAndDuplicateSlots() {
        val foreign = TradingSourceArtifact.restore(TradingSourceArtifactId(8), TradingImportSessionId(999),
            TradingArtifactRole.POSITION_HISTORY, null, "text/csv", 1, "a".repeat(64), "AUTO",
            "objects/foreign", byteArrayOf(1), "test", TradingArtifactRetentionStatus.ACTIVE,
            now, now.plusSeconds(86400), null, null, 0)
        val trade = artifact(4, TradingArtifactRole.TRADE_HISTORY)
        assertFalse(session.canValidate(listOf(trade, foreign)))
        assertFalse(session.canValidate(listOf(trade, artifact(6, trade.role), artifact(5, foreign.role))))
    }

    @Test
    fun rawKeyAndArtifactCollectionCannotBeMutatedOutsideRoot() {
        val key = byteArrayOf(1, 2, 3)
        val received = session.recordUpload(TradingSourceArtifactId(4), TradingArtifactRole.TRADE_HISTORY,
            "text/csv", 1, "a".repeat(64), "AUTO", "objects/test", key, "test", now)
        key.fill(0)
        val artifact = received.artifacts.single()
        artifact.wrappedDek!!.fill(0)
        assertEquals(listOf<Byte>(1, 2, 3), artifact.wrappedDek!!.toList())
        assertFailsWith<UnsupportedOperationException> { (received.artifacts as MutableList).clear() }
        assertFalse(artifact.rawReparseAvailable(artifact.rawExpiresAt))
    }

    private fun upload(root: TradingImportSession, id: Long, role: TradingArtifactRole) = root.recordUpload(
        TradingSourceArtifactId(id), role, "text/csv", 1, "a".repeat(64), "AUTO", "objects/$id", byteArrayOf(1), "test", now)

    private fun validating(): TradingImportSession {
        val uploaded = upload(upload(session, 4, TradingArtifactRole.TRADE_HISTORY), 5, TradingArtifactRole.POSITION_HISTORY)
        return TradingImportSession.restore(uploaded.id, uploaded.bookId, uploaded.owner, uploaded.sourceTimezone,
            uploaded.reviewTimezone, uploaded.declaredPositionMode, uploaded.dataPolicyVersion, uploaded.retentionPolicy,
            uploaded.maskingPolicyVersion, uploaded.evidenceRetention, uploaded.deletionPolicyVersion, uploaded.versionSetJson,
            TradingImportStatus.VALIDATING, 0, false, now, now, 0, now,
            NormalizationRunId("00000000-0000-0000-0000-000000000001"), artifacts = uploaded.artifacts)
    }

    @Test
    fun terminalClocksArePinnedOnceAndSurviveRawDeletion() {
        val root = validating()
        val at = now.plusSeconds(60)
        val revision = com.refinvest.core.tradingreview.domain.valueobject.LedgerRevisionId(java.util.UUID.randomUUID())
        val accepted = root.acceptNormalization(root.activeNormalizationRunId!!, 0, revision, at)!!
        assertEquals(at, accepted.acceptedAt)
        assertTrue(accepted.artifacts.all { it.rawExpiresAt == at.plusSeconds(7 * 86400) })
        assertEquals(null, accepted.acceptNormalization(root.activeNormalizationRunId, 0, revision, at.plusSeconds(1)))
        assertEquals(at, accepted.requestArtifactDeletion(TradingSourceArtifactId(4), at.plusSeconds(1)).acceptedAt)
        val rejected = root.rejectValidation(root.activeNormalizationRunId.value, 0,
            TradingImportFailure("FILE_FORMAT_INVALID", false, "NONE", at), at)!!
        assertEquals(at, rejected.rejectedAt)
        assertTrue(rejected.artifacts.all { it.rawExpiresAt == at.plusSeconds(86400) })
        assertEquals(at, rejected.requestArtifactDeletion(TradingSourceArtifactId(4), at.plusSeconds(2)).rejectedAt)
    }

    @Test
    fun rawExpiryFencesTheSessionAtTheBoundaryWithoutRevivingSource() {
        val root = validating()
        val deadline = now.plusSeconds(86400)
        assertEquals(null, root.expireRawSource(TradingSourceArtifactId(4), deadline.minusNanos(1)))
        assertEquals(null, root.acceptNormalization(root.activeNormalizationRunId!!, 0,
            com.refinvest.core.tradingreview.domain.valueobject.LedgerRevisionId(java.util.UUID.randomUUID()), deadline))
        assertEquals(null, root.rejectValidation(root.activeNormalizationRunId.value, 0,
            TradingImportFailure("LATE", false, "NONE", deadline), deadline))
        val expired = root.expireRawSource(TradingSourceArtifactId(4), deadline)!!
        assertEquals(deadline, expired.rawExpiredAt)
        assertEquals(1L, expired.deletionGeneration)
        assertEquals("SOURCE_ARTIFACT_EXPIRED", expired.failure?.code)
        assertEquals("REUPLOAD_REQUIRED", expired.failure?.userAction)
        assertFalse(expired.failure!!.retryable)
        assertFalse(expired.canUpload())
        assertEquals(null, expired.expireRawSource(TradingSourceArtifactId(5), deadline.plusSeconds(1)))
        assertEquals(root.artifacts.map { it.rawExpiresAt }, expired.artifacts.map { it.rawExpiresAt })
    }
}
