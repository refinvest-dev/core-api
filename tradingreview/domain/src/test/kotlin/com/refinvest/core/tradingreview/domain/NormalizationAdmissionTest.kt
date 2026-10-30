package com.refinvest.core.tradingreview.domain

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.importsession.*
import com.refinvest.core.tradingreview.domain.service.NormalizationInputCanonicalizer
import com.refinvest.core.tradingreview.domain.valueobject.*
import java.time.Instant
import kotlin.test.*

class NormalizationAdmissionTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val firstId = NormalizationRunId("00000000-0000-0000-0000-000000000001")
    private val secondId = NormalizationRunId("00000000-0000-0000-0000-000000000002")
    private val token = "00000000-0000-0000-0000-000000000003"
    private val delivery = NormalizationResultDeliveryPolicy("RESULT_DELIVERY_V1", "SINGLE_RESOURCE", 1000, listOf("identity"))
    private val versions = mapOf(
        "versionSetHash" to "f".repeat(64),
        "normalizer" to mapOf("sourceDialectVersionsByArtifactRole" to mapOf("TRADE_HISTORY" to "TRADE_V1", "POSITION_HISTORY" to "POSITION_V1"),
            "adapterVersion" to "ADAPTER_V1", "normalizerVersion" to "NORMALIZER_V1", "canonicalSchemaVersion" to "CANONICAL_V1",
            "implementationDigest" to "normalizer-digest", "versionSetHash" to "d".repeat(64)),
        "reconstruction" to mapOf("instrumentTermsVersionByInstrument" to emptyMap<String, String>(),
            "reconstructionPolicyVersion" to "RECONSTRUCTION_V1", "reconciliationVersion" to "RECONCILIATION_V1",
            "reviewUnitSchemaVersion" to "REVIEW_UNIT_V1", "evidenceSchemaVersion" to "EVIDENCE_V1",
            "implementationDigest" to "reconstruction-digest", "versionSetHash" to "e".repeat(64)),
    )
    private val versionJson = """{"versionSetHash":"${"f".repeat(64)}","normalizer":{"sourceDialectVersionsByArtifactRole":{"TRADE_HISTORY":"TRADE_V1","POSITION_HISTORY":"POSITION_V1"},"adapterVersion":"ADAPTER_V1","normalizerVersion":"NORMALIZER_V1","canonicalSchemaVersion":"CANONICAL_V1","implementationDigest":"normalizer-digest","versionSetHash":"${"d".repeat(64)}"},"reconstruction":{"instrumentTermsVersionByInstrument":{},"reconstructionPolicyVersion":"RECONSTRUCTION_V1","reconciliationVersion":"RECONCILIATION_V1","reviewUnitSchemaVersion":"REVIEW_UNIT_V1","evidenceSchemaVersion":"EVIDENCE_V1","implementationDigest":"reconstruction-digest","versionSetHash":"${"e".repeat(64)}"}}"""
    private val account = TradingAccount.create(TradingAccountId(1), MemberId(7), TradingVenue.BINANCE, "A", now)
    private val book = TradingBook.create(TradingBookId(2), account, "B", TradingProductFamily.LINEAR_PERPETUAL,
        TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", now)
    private val session = TradingImportSession.create(TradingImportSessionId(3), book, "UTC", "UTC",
        TradingPositionMode.ONE_WAY, TradingDataPolicy(), versionJson, now)
        .recordUpload(TradingSourceArtifactId(4), TradingArtifactRole.TRADE_HISTORY, "text/csv", 10,
            "a".repeat(64), "AUTO", "objects/trade", byteArrayOf(1), "test", now)
        .recordUpload(TradingSourceArtifactId(5), TradingArtifactRole.POSITION_HISTORY, "text/csv", 20,
            "b".repeat(64), "AUTO", "objects/position", byteArrayOf(2), "test", now)

    private fun input(root: TradingImportSession = session) = NormalizationInput(root.bookId, root.id, root.owner.value,
        root.artifacts.map { NormalizationArtifactInput(it.id, it.role, it.sha256, it.byteSize, it.contentType, it.dialectCandidate) },
        "BINANCE", "LINEAR_PERPETUAL", "ONE_WAY", "USDT", root.sourceTimezone, root.reviewTimezone,
        root.versionSetJson, "[]", root.dataPolicyVersion, "retry-v1", "{}", root.deletionGeneration)

    private fun admit(root: TradingImportSession = session, id: NormalizationRunId = firstId,
                      pin: NormalizationResultVersionPin = NormalizationResultVersionPin.V1,
                      prepared: NormalizationInput = input(root), at: Instant = now): TradingImportSession {
        val identity = NormalizationInputIdentity.fromPinnedInput(prepared, versions)
        val logical = NormalizationInputCanonicalizer.logicalKey(root.owner.value,
            NormalizationInputCanonicalizer.hash(identity), root.deletionGeneration, pin)
        return root.admitNormalization(id, prepared, logical, token, identity, versions, delivery, pin, at)
    }

    @Test
    fun rootCreatesOneOwnedRunWithExactPinsWithoutChangingTheOriginalSnapshot() {
        val admitted = admit()
        val run = assertNotNull(admitted.normalizationRun)
        assertEquals(TradingImportStatus.RECEIVED, session.status)
        assertNull(session.normalizationRun)
        assertEquals(TradingImportStatus.VALIDATING, admitted.status)
        assertEquals(firstId, admitted.activeNormalizationRunId)
        assertEquals(session.id, run.input.importSessionId)
        assertEquals(NormalizationRunStatus.DISPATCH_PENDING, run.status)
        assertEquals(1, run.currentAttempt)
        assertEquals(0L, run.version)
        assertEquals(NormalizationInputCanonicalizer.hash(assertNotNull(run.inputIdentity)), run.inputFingerprint)
        assertEquals(delivery, run.resultDeliveryPolicy)
        assertFailsWith<IllegalArgumentException> { admit(admitted, secondId) }
    }

    @Test
    fun admissionRejectsChangedOwnershipPinsDescriptorsAndRawAvailability() {
        val input = input()
        for (changed in listOf(input.copy(ownerId = 8), input.copy(tradingBookId = TradingBookId(999)),
            input.copy(importSessionId = TradingImportSessionId(999)), input.copy(sourceTimezone = "Asia/Seoul"),
            input.copy(versionSetJson = "changed"), input.copy(deletionGeneration = 1),
            input.copy(artifacts = input.artifacts.map { it.copy(contentLength = it.contentLength + 1) }),
            input.copy(artifacts = input.artifacts.map { it.copy(sha256 = "c".repeat(64)) }))) {
            assertFailsWith<IllegalArgumentException> { admit(prepared = changed) }
        }
        assertFailsWith<IllegalArgumentException> { admit(session.requestDeletion(now)) }
        assertFailsWith<IllegalArgumentException> { admit(at = now.plusSeconds(86400)) }
        val identity = NormalizationInputIdentity.fromPinnedInput(input, versions)
        assertFailsWith<IllegalArgumentException> { session.admitNormalization(firstId, input, "0".repeat(64),
            token, identity, versions, delivery, NormalizationResultVersionPin.V1, now) }
        assertFailsWith<IllegalArgumentException> { NormalizationRunId("not-a-run-id") }
    }

    @Test
    fun explicitRetryKeepsInputAndResultPairAndRequiresRetryableTerminalHistory() {
        val first = assertNotNull(admit(pin = NormalizationResultVersionPin.V2).normalizationRun)
        val terminal = NormalizationRun.restore(first.id, first.input, first.inputFingerprint, first.logicalKey,
            NormalizationRunStatus.TERMINAL_FAILURE, 1, first.attemptToken, null, "TEMPORARY_DEPENDENCY_FAILURE",
            now, now, null, now.plusSeconds(1), now.plusSeconds(1), 1, inputIdentity = first.inputIdentity,
            resultDeliveryPolicy = first.resultDeliveryPolicy, resultVersionPin = first.resultVersionPin)
        val failure = TradingImportFailure("TEMPORARY_DEPENDENCY_FAILURE", true, "RETRY", now.plusSeconds(1))
        val failed = restoreFailed(terminal, failure)
        val retry = assertNotNull(admit(failed, secondId, NormalizationResultVersionPin.V2, at = now.plusSeconds(2)).normalizationRun)
        assertEquals(first.id, retry.retryOfRunId)
        assertEquals(first.inputFingerprint, retry.inputFingerprint)
        assertEquals(first.logicalKey, retry.logicalKey)
        assertEquals(NormalizationResultVersionPin.V2, retry.resultVersionPin)
        assertFailsWith<IllegalArgumentException> { admit(failed, secondId, at = now.plusSeconds(2)) }
        assertFailsWith<IllegalArgumentException> { admit(failed, secondId, NormalizationResultVersionPin.V2,
            prepared = input(failed).copy(instrumentTermsJson = "changed-terms"), at = now.plusSeconds(2)) }
        assertFailsWith<IllegalArgumentException> { admit(restoreFailed(terminal,
            failure.copy(retryable = false)), secondId, NormalizationResultVersionPin.V2, at = now.plusSeconds(2)) }
        assertFailsWith<IllegalArgumentException> { admit(restoreFailed(first, failure), secondId,
            NormalizationResultVersionPin.V2, at = now.plusSeconds(2)) }
    }

    @Test
    fun runProtectsMutableInputAndTransportCollectionsAndRejectsInvalidRestoration() {
        val source = input().artifacts.toMutableList()
        val encodings = mutableListOf("identity")
        val run = NormalizationRun.restore(firstId, input().copy(artifacts = source), "a".repeat(64), "legacy-key",
            NormalizationRunStatus.DISPATCH_PENDING, 1, token, null, null, now, now, null, now, null, 0,
            resultDeliveryPolicy = delivery.copy(acceptedContentEncodings = encodings))
        source.clear(); encodings.clear()
        assertEquals(2, run.input.artifacts.size)
        assertEquals(listOf("identity"), run.resultDeliveryPolicy?.acceptedContentEncodings)
        assertFailsWith<UnsupportedOperationException> { (run.input.artifacts as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (run.resultDeliveryPolicy!!.acceptedContentEncodings as MutableList).clear() }
        assertNull(run.inputIdentity) // Historical absence is preserved, never upgraded silently.
        val valid = assertNotNull(admit().normalizationRun)
        assertFailsWith<IllegalArgumentException> { NormalizationRun.restore(valid.id,
            valid.input.copy(sourceTimezone = "Asia/Seoul"), valid.inputFingerprint, valid.logicalKey,
            valid.status, valid.currentAttempt, valid.attemptToken, null, null, now, now, null, now, null, 0,
            inputIdentity = valid.inputIdentity) }
        for ((attempt, version, status) in listOf(Triple(0, 0L, NormalizationRunStatus.DISPATCH_PENDING),
            Triple(1, -1L, NormalizationRunStatus.DISPATCH_PENDING), Triple(1, 0L, NormalizationRunStatus.PUBLISHED))) {
            assertFailsWith<IllegalArgumentException> { NormalizationRun.restore(firstId, valid.input, valid.inputFingerprint,
                valid.logicalKey, status, attempt, token, null, null, now, now, null, now, null, version,
                inputIdentity = valid.inputIdentity) }
        }
    }

    @Test
    fun logicalKeysKeepTheExistingCanonicalV1AndV2Shapes() {
        assertEquals("a90739186909cfd2262291d241234891be396b4360df8e67ba0eb737019ceca8",
            NormalizationInputCanonicalizer.logicalKey(7, "a".repeat(64), 0, NormalizationResultVersionPin.V1))
        assertEquals("5a59d76fc006cb3225abf735bb82f92ac1fdd916aafa07ec94db073d23f3efab",
            NormalizationInputCanonicalizer.logicalKey(7, "a".repeat(64), 0, NormalizationResultVersionPin.V2))
    }

    private fun restoreFailed(run: NormalizationRun, failure: TradingImportFailure) = TradingImportSession.restore(
        session.id, session.bookId, session.owner, session.sourceTimezone, session.reviewTimezone, session.declaredPositionMode,
        session.dataPolicyVersion, session.retentionPolicy, session.maskingPolicyVersion, session.evidenceRetention,
        session.deletionPolicyVersion, session.versionSetJson, TradingImportStatus.PROCESSING_FAILED, 0, false,
        now, now.plusSeconds(1), 1, now, run.id, failure, session.artifacts, normalizationRun = run)
}
