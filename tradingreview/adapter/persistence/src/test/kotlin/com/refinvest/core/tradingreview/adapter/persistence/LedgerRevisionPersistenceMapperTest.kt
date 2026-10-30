package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.LedgerRevision
import com.refinvest.core.tradingreview.domain.valueobject.*
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class LedgerRevisionPersistenceMapperTest {
    @Test
    fun v1AndV2MetadataRoundTripWithoutLoadingMembersOrReplayingPublication() {
        listOf(NormalizationResultVersionPin.V1, NormalizationResultVersionPin.V2).forEach { pin ->
            val original = revision(result(pin)).publish(3, Instant.parse("2026-01-01T00:00:00Z"))
            val record = LedgerRevisionPersistenceMapper.toRecord(original)
            val restored = LedgerRevisionPersistenceMapper.toDomain(record)
            assertEquals(record, LedgerRevisionPersistenceMapper.toRecord(restored))
            assertEquals(original.id, restored.id)
            assertEquals(original.inputSetHash, restored.inputSetHash)
            assertEquals(original.publication, restored.publication)
            assertEquals(pin, restored.result.versionPin)
        }
        assertFailsWith<IllegalArgumentException> { LedgerRevisionPersistenceMapper.toRecord(revision()) }
    }

    @Test
    fun restorationRejectsDriftedInputVersionsScopeCountsAndHashes() {
        val record = LedgerRevisionPersistenceMapper.toRecord(revision().publish(1, Instant.EPOCH))
        listOf(record.copy(inputSetHash = "0".repeat(64)), record.copy(bookId = 7),
            record.copy(inputHashSpecificationVersion = "unknown"), record.copy(targetVersionSetJson = "{}"),
            record.copy(resultSchemaVersion = NormalizationResultVersionPin.V2.schemaVersion),
            record.copy(countsJson = "{}"), record.copy(sectionDigestsJson = "[]"),
            record.copy(ledgerContentHash = "f".repeat(64)), record.copy(revisionNumber = 0)).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> { LedgerRevisionPersistenceMapper.toDomain(invalid) }
        }
    }

    @Test
    fun mapperRejectsFullCollectionPayloadsInTheBoundedHeader() {
        val record = LedgerRevisionPersistenceMapper.toRecord(revision().publish(1, Instant.EPOCH))
        assertFailsWith<IllegalArgumentException> { LedgerRevisionPersistenceMapper.toDomain(record.copy(
            manifestBaseJson = """{"reviewUnits":[{"reviewUnitId":"one"}]}""")) }
        assertFailsWith<IllegalArgumentException> { LedgerRevisionPersistenceMapper.toDomain(record.copy(
            qualityBaseJson = """{"validationIssues":[{"code":"one"}]}""")) }
        assertFailsWith<IllegalArgumentException> { LedgerRevisionPersistenceMapper.toDomain(record.copy(coverageJson = "{}")) }
    }

    private val versions = mapOf(
        "versionSetHash" to "f".repeat(64),
        "normalizer" to mapOf("sourceDialectVersionsByArtifactRole" to mapOf("TRADE_HISTORY" to "TRADE_V1", "POSITION_HISTORY" to "POSITION_V1"),
            "adapterVersion" to "ADAPTER_V1", "normalizerVersion" to "NORMALIZER_V1", "canonicalSchemaVersion" to "CANONICAL_V1",
            "implementationDigest" to "normalizer-digest", "versionSetHash" to "d".repeat(64)),
        "reconstruction" to mapOf("instrumentTermsVersionByInstrument" to emptyMap<String, String>(),
            "reconstructionPolicyVersion" to "RECONSTRUCTION_V1", "reconciliationVersion" to "RECONCILIATION_V1",
            "reviewUnitSchemaVersion" to "REVIEW_UNIT_V1", "evidenceSchemaVersion" to "EVIDENCE_V1",
            "implementationDigest" to "reconstruction-digest", "versionSetHash" to "e".repeat(64)))
    private val identity = NormalizationInputIdentity.fromWireValue(mapOf(
        "hashSpecificationVersion" to NormalizationInputIdentity.V1, "tradingBookId" to "2",
        "sourceImportSessionIds" to listOf("3"), "artifactsByRole" to mapOf(
            "TRADE_HISTORY" to mapOf("artifactId" to "4", "sha256" to "a".repeat(64)),
            "POSITION_HISTORY" to mapOf("artifactId" to "5", "sha256" to "b".repeat(64))),
        "sourceTimezone" to "UTC", "reviewTimezone" to "Asia/Seoul", "declaredProduct" to mapOf(
            "venue" to "BINANCE", "productFamily" to "LINEAR_PERPETUAL", "settlementAsset" to "USDT"),
        "declaredPositionMode" to "ONE_WAY", "targetVersionSet" to versions))
    private val counts = LedgerRevisionResult.COUNT_SECTIONS.mapValues { (key, _) -> if (key == "artifactCoverageCount") 2L else 0L }
    private fun digests(pin: NormalizationResultVersionPin) =
        (LedgerRevisionResult.COUNT_SECTIONS.values.filterNotNull().toSet() + setOf("QUALITY_REPORT", "RECONCILIATION_MANIFEST") +
            if (pin == NormalizationResultVersionPin.V2) setOf("ANALYTICS_INPUT_SUPPLEMENT") else emptySet()).map { section ->
            LedgerSectionDigest(section, if (section == "ARTIFACT_COVERAGES") 2L else if (section in setOf(
                "QUALITY_REPORT", "RECONCILIATION_MANIFEST", "ANALYTICS_INPUT_SUPPLEMENT")) 1L else 0L,
                pin.hashSpecificationVersion, "a".repeat(64)) }
    private fun result(pin: NormalizationResultVersionPin = NormalizationResultVersionPin.V1,
                       values: Map<String, Long> = counts, sections: List<LedgerSectionDigest> = digests(pin)) =
        LedgerRevisionResult(pin, "b".repeat(64), "a".repeat(64), "a".repeat(64), values, sections)
    private fun revision(result: LedgerRevisionResult = result()) = LedgerRevision.prepare(
        LedgerRevisionId(UUID.fromString("00000000-0000-0000-0000-000000000001")), MemberId(1),
        NormalizationRunId("00000000-0000-0000-0000-000000000002"), TradingBookId(2), TradingImportSessionId(3),
        identity, result, LedgerRevisionMetadata("{}", "[]", "[]", "{}"))
}
