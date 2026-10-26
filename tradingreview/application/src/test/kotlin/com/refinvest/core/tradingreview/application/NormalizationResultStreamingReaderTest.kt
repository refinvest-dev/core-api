package com.refinvest.core.tradingreview.application

import com.refinvest.core.tradingreview.port.NormalizationStagedItem
import com.refinvest.core.tradingreview.port.TradingNormalizationResultStore
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.lang.reflect.Proxy
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
    private val v2 = mapper.readTree(requireNotNull(javaClass.getResourceAsStream(
        "/trading-normalization/result-integrity-v2.json")))

    @Test
    fun v2SupplementRowsStreamAndMatchFixedResultDigest() {
        val rows = mutableListOf<NormalizationStagedItem>()
        val base = reader.read(ByteArrayInputStream(
            mapper.writeValueAsBytes(v2.path("terminalPayload")))) { section, id, value ->
            rows.add(NormalizationStagedItem(section, id,
                NormalizationResultCanonicalizer.hash(value), mapper.writeValueAsString(value)))
        }
        assertEquals(emptyList<Any>(),
            (base["analyticsInputSupplement"] as Map<*, *>)["reviewUnitEngineInputs"])
        assertEquals(1, rows.count { it.sectionType == "ANALYTICS_INPUT_SUPPLEMENT_ROWS" })
        assertEquals(1, rows.count { it.sectionType == "ANALYTICS_INPUT_SUPPLEMENT" })
        val verified = verifyStaged(base, rows)
        assertEquals(v2.path("resultDigestManifestVector").path("expectedSha256").asText(),
            verified.resultHash)
    }

    @Test
    fun v2RejectsMissingMoneyAndMissingDirectEvidence() {
        for ((field, replacement, expected) in listOf(
            Triple("allocatedFee", null, "RESULT_SCHEMA_UNSUPPORTED"),
            Triple("evidenceRecordIds", emptyList<String>(), "EVIDENCE_REFERENCE_INVALID"))) {
            @Suppress("UNCHECKED_CAST")
            val payload = mapper.readValue(mapper.writeValueAsBytes(v2.path("terminalPayload")),
                Map::class.java) as MutableMap<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val supplement = payload.getValue("analyticsInputSupplement") as MutableMap<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val unit = (supplement.getValue("reviewUnitEngineInputs") as MutableList<MutableMap<String, Any?>>)[0]
            if (field == "allocatedFee") {
                @Suppress("UNCHECKED_CAST")
                val allocation = (unit.getValue("allocationEvidence") as MutableList<MutableMap<String, Any?>>)[0]
                allocation[field] = replacement
            } else unit[field] = replacement
            val rows = mutableListOf<NormalizationStagedItem>()
            val base = reader.read(ByteArrayInputStream(mapper.writeValueAsBytes(payload))) { section, id, value ->
                rows.add(NormalizationStagedItem(section, id,
                    NormalizationResultCanonicalizer.hash(value), mapper.writeValueAsString(value)))
            }
            assertEquals(expected, assertFailsWith<ResultIntegrityFailure> {
                verifyStaged(base, rows)
            }.code)
        }
    }

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
        val verified = verifyStaged(base, rows)
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
    fun parserCapacityLimitsStopOversizedTokensAndDeepObjectsBeforeStaging() {
        val limited = NormalizationResultStreamingReader(mapper,
            NormalizationResultReadLimits(maxNestingDepth = 3, maxStringLength = 8,
                maxNameLength = 64, maxTokenCount = 100))
        var staged = 0
        val oversized = assertFailsWith<ResultIntegrityFailure> {
            limited.read(ByteArrayInputStream(
                "{\"canonicalRecords\":[{\"recordId\":\"a-very-long-record-id\"}]}".toByteArray()))
            { _, _, _ -> staged++ }
        }
        assertEquals("TEMPORARY_DEPENDENCY_FAILURE", oversized.code)
        assertEquals(0, staged)

        val deep = assertFailsWith<ResultIntegrityFailure> {
            limited.read(ByteArrayInputStream(
                "{\"canonicalRecords\":[{\"recordId\":\"short\",\"nested\":{\"a\":{}}}]}".toByteArray()))
            { _, _, _ -> staged++ }
        }
        assertEquals("TEMPORARY_DEPENDENCY_FAILURE", deep.code)
        assertEquals(0, staged)
    }

    @Test
    fun parserTokenLimitAppliesAcrossTheEntireResource() {
        val limited = NormalizationResultStreamingReader(mapper,
            NormalizationResultReadLimits(maxTokenCount = 8))
        val failure = assertFailsWith<ResultIntegrityFailure> {
            limited.read(ByteArrayInputStream(
                "{\"canonicalRecords\":[{\"recordId\":\"one\"},{\"recordId\":\"two\"}]}".toByteArray()))
            { _, _, _ -> }
        }
        assertEquals("TEMPORARY_DEPENDENCY_FAILURE", failure.code)
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
            verifyStaged(base, rows)
        }
        assertEquals("RECONCILIATION_MANIFEST_HASH_MISMATCH", failure.code)
    }

    @Test
    fun legacyStageFormatRequiresAuthenticatedRedownload() {
        val rows = mutableListOf<NormalizationStagedItem>()
        val base = reader.read(ByteArrayInputStream(
            mapper.writeValueAsBytes(vector.path("terminalPayload")))) { section, id, value ->
            rows.add(NormalizationStagedItem(section, id,
                NormalizationResultCanonicalizer.hash(value), mapper.writeValueAsString(value)))
        }
        @Suppress("UNCHECKED_CAST")
        val legacy = base + (NormalizationResultStreamingReader.STAGE_FORMAT_FIELD to "STREAM_V1")
        assertEquals("RESULT_SCHEMA_UNSUPPORTED", assertFailsWith<ResultIntegrityFailure> {
            verifyStaged(legacy, rows)
        }.code)
    }

    private fun verifyStaged(base: Map<String, Any?>,
                             rows: List<NormalizationStagedItem>): VerifiedStagedResult {
        val declared = base["counts"] as Map<*, *>
        val counts = rows.groupingBy { it.sectionType }.eachCount().mapValues { it.value.toLong() } +
            mapOf("POSITION_ALLOCATIONS" to (declared["positionAllocationCount"] as Number).toLong(),
                "EXCLUDED_REVIEW_UNITS" to (declared["excludedReviewUnitCount"] as Number).toLong())
        val store = Proxy.newProxyInstance(javaClass.classLoader,
            arrayOf(TradingNormalizationResultStore::class.java)) { _, method, args ->
            when (method.name) {
                "stagedCounts" -> counts
                "hasInvalidSupplement" -> false
                "visitItems" -> {
                    @Suppress("UNCHECKED_CAST")
                    val accept = args!![3] as (NormalizationStagedItem) -> Unit
                    rows.asSequence().filter { it.sectionType == args[1] }
                        .sortedBy { it.identity }.forEach(accept)
                    Unit
                }
                else -> error("Unexpected store call: ${method.name}")
            }
        } as TradingNormalizationResultStore
        return NormalizationStagedResultVerifier(store, mapper, 2).verify(base)
    }
}
