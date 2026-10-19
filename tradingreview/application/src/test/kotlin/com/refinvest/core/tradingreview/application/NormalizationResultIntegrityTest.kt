package com.refinvest.core.tradingreview.application

import tools.jackson.databind.json.JsonMapper
import java.nio.charset.StandardCharsets
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NormalizationResultIntegrityTest {
    private val mapper = JsonMapper.builder().build()
    private val vector = mapper.readTree(requireNotNull(javaClass.getResourceAsStream(
        "/trading-normalization/result-integrity-v1.json")))

    @Suppress("UNCHECKED_CAST")
    private fun payload(): MutableMap<String, Any?> = mapper.readValue(
        mapper.writeValueAsString(vector.path("terminalPayload")), Map::class.java) as MutableMap<String, Any?>

    @Test
    fun contextVectorMatchesEverySectionManifestAndOverallHash() {
        val result = NormalizationResultVerifier.verify(payload())
        assertEquals(vector.path("resultDigestManifestVector").path("expectedSha256").asText(), result.resultHash)
        assertEquals(vector.path("manifestVector").path("expectedSha256").asText(), result.manifestHash)
        val sections = vector.path("sectionVectors")
        for (digest in result.sectionDigests) {
            val type = digest.getValue("sectionType") as String
            assertEquals(sections.path(type).path("expectedSha256").asText(), digest.getValue("sectionHash"))
            val bytes = NormalizationResultCanonicalizer.canonicalize(result.sectionValues.getValue(type))
            assertContentEquals(sections.path(type).path("canonicalJson").asText()
                .toByteArray(StandardCharsets.UTF_8), bytes)
            assertEquals(sections.path(type).path("canonicalUtf8Hex").asText(),
                HexFormat.of().formatHex(bytes))
        }
        val manifestBytes = NormalizationResultCanonicalizer.canonicalize(
            (result.public["reconciliationManifest"] as Map<*, *>) - "manifestHash")
        assertContentEquals(sections.path("RECONCILIATION_MANIFEST").path("canonicalJson").asText()
            .toByteArray(StandardCharsets.UTF_8), manifestBytes)
        assertEquals(vector.path("manifestVector").path("canonicalUtf8Hex").asText(),
            HexFormat.of().formatHex(manifestBytes))
    }

    @Test
    fun countAndHashDriftCannotPublish() {
        val wrongCount = payload()
        @Suppress("UNCHECKED_CAST")
        (wrongCount["counts"] as MutableMap<String, Any?>)["canonicalRecordCount"] = 2
        assertEquals("RESULT_COUNT_MISMATCH",
            assertFailsWith<ResultIntegrityFailure> { NormalizationResultVerifier.verify(wrongCount) }.code)
        val wrongHash = payload()
        wrongHash["resultHash"] = "0".repeat(64)
        assertEquals("RESULT_HASH_MISMATCH",
            assertFailsWith<ResultIntegrityFailure> { NormalizationResultVerifier.verify(wrongHash) }.code)
    }

    @Test
    fun sectionManifestAndEvidenceFailuresStayDistinct() {
        @Suppress("UNCHECKED_CAST")
        fun mutated(change: (MutableMap<String, Any?>) -> Unit): String {
            val value = payload()
            change(value)
            return assertFailsWith<ResultIntegrityFailure> {
                NormalizationResultVerifier.verify(value)
            }.code
        }
        assertEquals("RESULT_SECTION_HASH_MISMATCH", mutated { root ->
            @Suppress("UNCHECKED_CAST")
            val records = root["canonicalRecords"] as MutableList<MutableMap<String, Any?>>
            @Suppress("UNCHECKED_CAST")
            val price = records[0]["price"] as MutableMap<String, Any?>
            price["value"] = "9.99"
        })
        assertEquals("RECONCILIATION_MANIFEST_HASH_MISMATCH", mutated { root ->
            @Suppress("UNCHECKED_CAST")
            val manifest = root["reconciliationManifest"] as MutableMap<String, Any?>
            manifest["manifestHash"] = "0".repeat(64)
        })
        assertEquals("EVIDENCE_REFERENCE_INVALID", mutated { root ->
            @Suppress("UNCHECKED_CAST")
            val records = root["canonicalRecords"] as MutableList<MutableMap<String, Any?>>
            records[0]["sourceEvidenceSnapshotId"] = "missing"
        })
        assertEquals("RESULT_ITEM_CONFLICT", mutated { root ->
            @Suppress("UNCHECKED_CAST")
            val records = root["canonicalRecords"] as MutableList<MutableMap<String, Any?>>
            records.add(records[0].toMutableMap().also { it["side"] = "SELL" })
        })
    }

    @Test
    fun jcsNormalizesDecimalAndTimestampWithoutTouchingEvidenceText() {
        assertEquals("1.23", NormalizationResultCanonicalizer.normalize("01.2300", "value"))
        assertEquals("0", NormalizationResultCanonicalizer.normalize("-0.000", "amount"))
        assertEquals("2025-01-01T00:00:00.12Z",
            NormalizationResultCanonicalizer.normalize("2025-01-01T09:00:00.120+09:00", "occurredAt"))
        assertEquals(listOf(mapOf("name" to "kind", "value" to "synthetic")),
            NormalizationResultCanonicalizer.normalize(mapOf("allowlistedFields" to
                listOf(mapOf("name" to "kind", "value" to "synthetic"))))
                .let { (it as Map<*, *>)["allowlistedFields"] })
        assertEquals("RESULT_SCHEMA_UNSUPPORTED", assertFailsWith<ResultIntegrityFailure> {
            NormalizationResultCanonicalizer.canonicalize(mapOf("x" to Double.NaN))
        }.code)
    }
}
