package com.refinvest.core.tradingreview.application

import com.refinvest.core.tradingreview.port.NormalizationStagedItem
import com.refinvest.core.tradingreview.port.TradingNormalizationResultStore
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayInputStream
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NormalizationStagedResultVerifierTest {
    private val mapper = JsonMapper.builder().build()
    private val vector = mapper.readTree(requireNotNull(javaClass.getResourceAsStream(
        "/trading-normalization/result-integrity-v1.json")))

    @Test
    fun fixedVectorMatchesFromOrderedStagedRowsWithoutRehydratingThePayload() {
        val rows = mutableListOf<NormalizationStagedItem>()
        val reader = NormalizationResultStreamingReader(mapper)
        val base = reader.read(ByteArrayInputStream(
            mapper.writeValueAsBytes(vector.path("terminalPayload")))) { section, id, value ->
            rows.add(NormalizationStagedItem(section, id,
                NormalizationResultCanonicalizer.hash(value), mapper.writeValueAsString(value)))
        }
        @Suppress("UNCHECKED_CAST")
        val payload = mapper.readValue(mapper.writeValueAsBytes(vector.path("terminalPayload")),
            Map::class.java) as Map<String, Any?>
        val declared = payload["counts"] as Map<*, *>
        val counts = rows.groupingBy { it.sectionType }.eachCount().mapValues { it.value.toLong() } +
            mapOf("POSITION_ALLOCATIONS" to (declared["positionAllocationCount"] as Number).toLong(),
                "EXCLUDED_REVIEW_UNITS" to (declared["excludedReviewUnitCount"] as Number).toLong())
        val store = Proxy.newProxyInstance(javaClass.classLoader,
            arrayOf(TradingNormalizationResultStore::class.java)) { _, method, args ->
            when (method.name) {
                "stagedCounts" -> counts
                "visitItems" -> {
                    @Suppress("UNCHECKED_CAST")
                    val accept = args!![3] as (NormalizationStagedItem) -> Unit
                    rows.asSequence().filter { it.sectionType == args[1] }
                        .sortedBy { it.identity }.forEach(accept)
                    Unit
                }
                else -> error("Unexpected result store call: ${method.name}")
            }
        } as TradingNormalizationResultStore
        val result = NormalizationStagedResultVerifier(store, mapper, 2).verify(base)
        assertEquals(vector.path("manifestVector").path("expectedSha256").asText(),
            result.manifestHash)
        assertEquals(vector.path("resultDigestManifestVector").path("expectedSha256").asText(),
            result.resultHash)
        for (digest in result.sectionDigests) {
            val section = digest.getValue("sectionType") as String
            assertEquals(vector.path("sectionVectors").path(section).path("expectedSha256").asText(),
                digest.getValue("sectionHash"))
        }
        val recordIndex = rows.indexOfFirst { it.sectionType == "CANONICAL_RECORDS" }
        rows[recordIndex] = rows[recordIndex].copy(identity = "different-record")
        assertEquals("RESULT_ITEM_CONFLICT", assertFailsWith<ResultIntegrityFailure> {
            NormalizationStagedResultVerifier(store, mapper, 2).verify(base)
        }.code)
    }

    @Test
    fun stagingKeepsTheSameNestedOrderingThatPublicationWillStore() {
        val verifier = NormalizationStagedResultVerifier(
            Proxy.newProxyInstance(javaClass.classLoader,
                arrayOf(TradingNormalizationResultStore::class.java)) { _, _, _ ->
                error("No database access is needed to normalize an item")
            } as TradingNormalizationResultStore, mapper, 2)
        val allocations = listOf(mapOf("allocationId" to "z"),
            mapOf("allocationId" to "a"))
        val raw = mapOf(
            "reviewUnitId" to "unit-1",
            "allocations" to allocations,
            "capability" to listOf("Z", "A"),
            "exclusionReasons" to listOf("Z", "A"),
        )
        @Suppress("UNCHECKED_CAST")
        val staged = verifier.normalizeForStage("REVIEW_UNITS", raw) as Map<String, Any?>
        assertEquals(listOf("a", "z"), (staged["allocations"] as List<*>)
            .map { (it as Map<*, *>)["allocationId"] })
        assertEquals(listOf("A", "Z"), staged["capability"])
        assertEquals(listOf("A", "Z"), staged["exclusionReasons"])
        assertEquals(listOf("z", "a"), allocations.map { it["allocationId"] })
    }
}
