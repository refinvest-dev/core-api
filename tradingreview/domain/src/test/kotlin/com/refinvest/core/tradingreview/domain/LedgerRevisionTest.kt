package com.refinvest.core.tradingreview.domain

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.valueobject.*
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class LedgerRevisionTest {
    @Test
    fun publicationAssignsAnOrdinalWithoutChangingContentOrAllowingRepublish() {
        val draft = revision()
        val at = Instant.parse("2026-01-01T00:00:00Z")
        val published = draft.publish(7, at)
        assertNull(draft.publication)
        assertEquals(LedgerPublicationStamp(7, at), published.publication)
        assertSame(draft.inputIdentity, published.inputIdentity)
        assertSame(draft.result, published.result)
        assertEquals(draft.id, published.id)
        assertFailsWith<IllegalStateException> { published.publish(8, at) }
        assertFailsWith<IllegalArgumentException> { draft.publish(0, at) }
        assertFailsWith<IllegalArgumentException> { LedgerRevision.restore(draft.id, draft.owner, draft.runId,
            TradingBookId(9), draft.importSessionId, draft.inputIdentity, draft.result, draft.metadata,
            requireNotNull(published.publication)) }
    }

    @Test
    fun resultPinsCountsAndSectionMembershipCannotDisagree() {
        listOf(NormalizationResultVersionPin.V1, NormalizationResultVersionPin.V2).forEach { pin ->
            assertEquals(pin, revision(result(pin)).result.versionPin)
            assertFailsWith<IllegalArgumentException> { result(pin, counts + ("canonicalRecordCount" to -1L)) }
            assertFailsWith<IllegalArgumentException> { result(pin, counts - "reviewUnitCount") }
            assertFailsWith<IllegalArgumentException> { result(pin, counts + ("excludedReviewUnitCount" to 1L)) }
            assertFailsWith<IllegalArgumentException> { result(pin, sections = digests(pin).drop(1)) }
            assertFailsWith<IllegalArgumentException> { result(pin, sections = digests(pin) + digests(pin).first()) }
            assertFailsWith<IllegalArgumentException> { result(pin, sections = digests(pin).map {
                if (it.sectionType == "CANONICAL_RECORDS") it.copy(sectionHash = "c".repeat(64)) else it }) }
        }
        assertFailsWith<IllegalArgumentException> { LedgerSectionDigest("REVIEW_UNITS", 0, "V1", "invalid") }
    }

    @Test
    fun resultDoesNotExposeMutableCountOrDigestCollections() {
        val originalCounts = counts.toMutableMap()
        val originalDigests = digests(NormalizationResultVersionPin.V1).toMutableList()
        val frozen = result(values = originalCounts, sections = originalDigests)
        originalCounts.clear()
        originalDigests.clear()
        assertEquals(10, frozen.counts.size)
        assertEquals(9, frozen.sectionDigests.size)
        assertFailsWith<UnsupportedOperationException> { (frozen.counts as MutableMap).clear() }
        assertFailsWith<UnsupportedOperationException> { (frozen.sectionDigests as MutableList).clear() }
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
