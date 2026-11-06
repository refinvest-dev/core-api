package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.tradingreview.domain.importsession.*
import com.refinvest.core.tradingreview.domain.service.NormalizationInputCanonicalizer
import com.refinvest.core.tradingreview.domain.valueobject.*
import java.time.Instant
import tools.jackson.databind.json.JsonMapper
import kotlin.test.*

class NormalizationRunPersistenceMapperTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val json = JsonMapper.builder().build()
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
    private val input = NormalizationInput(TradingBookId(1), TradingImportSessionId(2), 7,
        listOf(NormalizationArtifactInput(TradingSourceArtifactId(3), TradingArtifactRole.TRADE_HISTORY, "a".repeat(64), 10, "text/csv", "AUTO"),
            NormalizationArtifactInput(TradingSourceArtifactId(4), TradingArtifactRole.POSITION_HISTORY, "b".repeat(64), 20, "text/csv", "AUTO")),
        "BINANCE", "LINEAR_PERPETUAL", "ONE_WAY", "USDT", "UTC", "UTC", json.writeValueAsString(versions),
        "[]", "TRADING_DATA_POLICY_V1", "retry-v1", "{}", 0)
    private val identity = NormalizationInputIdentity.fromPinnedInput(input, versions)
    private val hash = NormalizationInputCanonicalizer.hash(identity)
    private val policy = NormalizationResultDeliveryPolicy("RESULT_DELIVERY_V1", "SINGLE_RESOURCE", 1000, listOf("identity"))

    private fun record(pin: NormalizationResultVersionPin = NormalizationResultVersionPin.V1) =
        NormalizationRunPersistenceMapper.toRecord(NormalizationRun.restore(
            NormalizationRunId("00000000-0000-0000-0000-000000000001"), input, hash,
            NormalizationInputCanonicalizer.logicalKey(7, hash, 0, pin), NormalizationRunStatus.DISPATCH_PENDING,
            1, "00000000-0000-0000-0000-000000000002", null, null, now, now, null, now, null, 0,
            inputIdentity = identity, resultDeliveryPolicy = policy, resultVersionPin = pin))

    @Test
    fun restorationPreservesAllRunStatesAndV1V2PinsWithoutAdvancingTheAttempt() {
        for (pin in listOf(NormalizationResultVersionPin.V1, NormalizationResultVersionPin.V2)) {
            for (status in NormalizationRunStatus.entries) {
                val terminal = status in setOf(NormalizationRunStatus.PUBLISHED, NormalizationRunStatus.TERMINAL_FAILURE, NormalizationRunStatus.OBSOLETE)
                val stored = record(pin).copy(status = status.name, currentAttempt = 3, version = 12,
                    retryOfRunId = "00000000-0000-0000-0000-000000000003", computeJobId = "stored-job",
                    lastFailureCode = "STORED_FAILURE", startedAt = now, updatedAt = now.plusSeconds(2),
                    completedAt = if (terminal) now.plusSeconds(2) else null)
                val restored = NormalizationRunPersistenceMapper.toDomain(stored)
                assertEquals(3, restored.currentAttempt)
                assertEquals(12L, restored.version)
                assertEquals(pin, restored.resultVersionPin)
                assertEquals(stored, NormalizationRunPersistenceMapper.toRecord(restored))
            }
        }
    }

    @Test
    fun inconsistentIdentityDescriptorVersionAndStateAreRejectedDuringRestoration() {
        val r = record()
        for (invalid in listOf(r.copy(id = "invalid"), r.copy(version = -1), r.copy(currentAttempt = 0),
            r.copy(inputFingerprint = "0".repeat(64)), r.copy(inputJson = r.inputJson.replace("BINANCE", "OTHER")),
            r.copy(targetVersionSetJson = r.targetVersionSetJson.replace("ADAPTER_V1", "CHANGED")),
            r.copy(updatedAt = now.minusSeconds(1)), r.copy(status = "PUBLISHED"),
            r.copy(resultSchemaVersion = NormalizationResultVersionPin.V2.schemaVersion))) {
            assertFailsWith<IllegalArgumentException> { NormalizationRunPersistenceMapper.toDomain(invalid) }
        }
    }

    @Test
    fun legacyMissingIdentityPolicyAndCompletionArePreservedWithoutInventingExecutionPins() {
        val legacy = record().copy(inputJson = "{}", inputIdentityJson = null, resultDeliveryPolicyJson = null,
            status = "PUBLISHED", completedAt = null)
        val restored = NormalizationRunPersistenceMapper.toDomain(legacy)
        assertNull(restored.inputIdentity)
        assertNull(restored.resultDeliveryPolicy)
        assertNull(restored.completedAt)
        assertTrue(restored.input.artifacts.isEmpty())
        assertEquals(NormalizationRunStatus.PUBLISHED, restored.status)
        assertEquals(legacy.inputFingerprint, restored.inputFingerprint)
        assertEquals(legacy.logicalKey, restored.logicalKey)
        assertEquals(1, restored.currentAttempt)
    }
}
