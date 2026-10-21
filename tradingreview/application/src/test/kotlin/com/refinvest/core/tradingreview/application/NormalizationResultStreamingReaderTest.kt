package com.refinvest.core.tradingreview.application

import com.refinvest.core.tradingreview.port.NormalizationStagedItem
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NormalizationResultStreamingReaderTest {
    private val mapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()
    private val reader = NormalizationResultStreamingReader(mapper)
    private val vector = mapper.readTree(requireNotNull(javaClass.getResourceAsStream(
        "/trading-normalization/result-integrity-v1.json")))

    @Test
    fun fixedResultVectorStreamsCollectionsAndStillVerifiesAfterStaging() {
        val rows = mutableListOf<NormalizationStagedItem>()
        val bytes = mapper.writeValueAsBytes(vector.path("terminalPayload"))
        val base = reader.read(ByteArrayInputStream(bytes)) { section, id, value ->
            rows.add(NormalizationStagedItem(section, id,
                NormalizationResultCanonicalizer.hash(value), mapper.writeValueAsString(value)))
        }
        assertEquals(emptyList<Any>(), base["canonicalRecords"])
        assertEquals(emptyList<Any>(), (base["reconciliationManifest"] as Map<*, *>)["reviewUnits"])
        assertTrue(rows.any { it.sectionType == "CANONICAL_RECORDS" })
        assertEquals(1, rows.count { it.sectionType == "RECONCILIATION_MANIFEST" })
        assertTrue(rows.any { it.sectionType == "VALIDATION_ISSUES" })
        assertEquals(emptyList<Any>(), (rows.single { it.sectionType == "QUALITY_REPORT" }.let {
            mapper.readValue(it.publicJson, Map::class.java) } as Map<*, *>)["validationIssues"])
        val verified = NormalizationResultVerifier.verify(reader.rehydrate(base, rows))
        assertEquals(vector.path("resultDigestManifestVector").path("expectedSha256").asText(),
            verified.resultHash)
        assertEquals(vector.path("manifestVector").path("expectedSha256").asText(),
            verified.manifestHash)
    }

    @Test
    fun truncatedAndTrailingPayloadCannotBeStagedAsComplete() {
        assertFailsWith<ResultIntegrityFailure> {
            reader.read(ByteArrayInputStream("{\"canonicalRecords\":[".toByteArray())) { _, _, _ -> }
        }
        assertFailsWith<ResultIntegrityFailure> {
            reader.read(ByteArrayInputStream("{}{}".toByteArray())) { _, _, _ -> }
        }
    }

    @Test
    fun repeatedJsonPropertyIsRejected() {
        assertEquals("RESULT_PAYLOAD_PARSE_FAILED", assertFailsWith<ResultIntegrityFailure> {
            reader.read(ByteArrayInputStream("{\"canonicalRecords\":[],\"canonicalRecords\":[]}".toByteArray()))
            { _, _, _ -> }
        }.code)
    }

    @Test
    fun collectionItemsReachStagingBeforeTheWholePayloadIsRead() {
        val records = (1..500).joinToString(",") { "{\"recordId\":\"record-$it\"}" }
        val bytes = "{\"canonicalRecords\":[$records]}".toByteArray()
        var consumed = 0
        val input = object : FilterInputStream(ByteArrayInputStream(bytes)) {
            override fun read(): Int = super.read().also { if (it >= 0) consumed++ }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                `in`.read(buffer, offset, length).also { if (it > 0) consumed += it }
        }
        var staged = 0
        reader.read(input) { section, _, _ ->
            assertEquals("CANONICAL_RECORDS", section)
            if (staged == 0) assertTrue(consumed < bytes.size)
            staged++
        }
        assertEquals(500, staged)
    }

    @Test
    fun unknownRootFieldFailsBeforeParsingItsContent() {
        val failure = assertFailsWith<ResultIntegrityFailure> {
            reader.read(ByteArrayInputStream("{\"uncontracted\":[".toByteArray())) { _, _, _ -> }
        }
        assertEquals("RESULT_SCHEMA_UNSUPPORTED", failure.code)
    }

    @Test
    fun streamedManifestCannotBorrowMissingValuesFromTheRoot() {
        @Suppress("UNCHECKED_CAST")
        val payload = mapper.readValue(mapper.writeValueAsBytes(vector.path("terminalPayload")),
            Map::class.java) as MutableMap<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val manifest = payload.getValue("reconciliationManifest") as MutableMap<String, Any?>
        manifest["artifactCoverages"] = emptyList<Any>()
        val rows = mutableListOf<NormalizationStagedItem>()
        val base = reader.read(ByteArrayInputStream(mapper.writeValueAsBytes(payload))) { section, id, value ->
            rows.add(NormalizationStagedItem(section, id,
                NormalizationResultCanonicalizer.hash(value), mapper.writeValueAsString(value)))
        }
        val failure = assertFailsWith<ResultIntegrityFailure> {
            NormalizationResultVerifier.verify(reader.rehydrate(base, rows))
        }
        assertEquals("RECONCILIATION_MANIFEST_HASH_MISMATCH", failure.code)
    }

    @Test
    fun previouslyStagedBaseCanBeRehydrated() {
        val rows = mutableListOf<NormalizationStagedItem>()
        val base = reader.read(ByteArrayInputStream(
            mapper.writeValueAsBytes(vector.path("terminalPayload")))) { section, id, value ->
            rows.add(NormalizationStagedItem(section, id,
                NormalizationResultCanonicalizer.hash(value), mapper.writeValueAsString(value)))
        }
        @Suppress("UNCHECKED_CAST")
        val oldManifest = (base.getValue("reconciliationManifest") as Map<String, Any?>) + mapOf(
            "artifactCoverages" to emptyList<Any>(), "capability" to emptyList<Any>())
        val oldBase = (base - NormalizationResultStreamingReader.STAGE_FORMAT_FIELD) +
            ("reconciliationManifest" to oldManifest)
        val oldQuality = mapper.readValue(
            mapper.writeValueAsBytes(vector.path("terminalPayload").path("qualityReport")),
            Map::class.java)
        val oldRows = rows.filter { it.sectionType != "VALIDATION_ISSUES" }.map { item ->
            if (item.sectionType == "QUALITY_REPORT") NormalizationStagedItem(
                item.sectionType, item.identity, NormalizationResultCanonicalizer.hash(oldQuality),
                mapper.writeValueAsString(oldQuality)) else item
        }
        val result = NormalizationResultVerifier.verify(reader.rehydrate(oldBase, oldRows))
        assertEquals(vector.path("resultDigestManifestVector").path("expectedSha256").asText(),
            result.resultHash)
    }
}
