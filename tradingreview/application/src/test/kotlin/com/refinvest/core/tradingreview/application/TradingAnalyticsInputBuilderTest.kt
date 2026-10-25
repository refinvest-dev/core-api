package com.refinvest.core.tradingreview.application

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.port.*
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TradingAnalyticsInputBuilderTest {
    private val mapper = JsonMapper.builder().build()
    private val vector = mapper.readValue(requireNotNull(javaClass.getResourceAsStream(
        "/trading-analytics-protocol/v1/input-identity-v1.json")), Map::class.java)
    private val samples = map(vector["artifactSamples"])
    private val ledger = map(samples["ledgerRevisionContent"])
    private val manifest = map(samples["reconciliationManifest"])
    private val revision = UUID.randomUUID().toString()
    private val storage = MemoryStore()
    private val source = object : TradingAnalyticsRevisionReader {
        var version = NormalizationResultVerifier.RESULT_SCHEMA_V2
        override fun find(revisionId: String, ownerId: Long): AnalyticsRevisionSource? =
            AnalyticsRevisionSource(revisionId, version, string(ledger["ledgerContentHash"]),
                string(manifest["manifestHash"]), string(ledger["canonicalSchemaVersion"]),
                string(ledger["reviewUnitSchemaVersion"]),
                mapper.writeValueAsString(listOf(map((ledger["instrumentDescriptors"] as List<*>).single())
                    - "sourceSymbol")), mapper.writeValueAsString(manifest),
                mapper.writeValueAsString(manifest["capability"]), 1, 1)
        override fun visitRecords(revisionId: String, accept: (String) -> Unit) {
            (ledger["canonicalRecords"] as List<*>).forEach { accept(mapper.writeValueAsString(it)) }
        }
        override fun visitEvidenceReferences(revisionId: String, accept: (String) -> Unit) {
            (ledger["sourceEvidenceReferences"] as List<*>).forEach { accept(mapper.writeValueAsString(it)) }
        }
        override fun visitEngineInputs(revisionId: String, accept: (String) -> Unit) {
            (ledger["reviewUnitEngineInputs"] as List<*>).forEach { accept(mapper.writeValueAsString(it)) }
        }
    }
    private val members = object : TradingMemberProvider {
        override fun currentMemberId() = MemberId(1)
    }
    private val builder = TradingAnalyticsInputBuilder(source, storage, members, mapper)

    @Test
    fun createsTheTwoCanonicalArtifactsDeterministicallyFromPinnedRevision() {
        val first = builder.prepare(revision)
        val second = builder.prepare(revision)
        assertEquals(first, second)
        val expectedLedger = NormalizationResultCanonicalizer.canonicalize(
            ledger + ("ledgerRevisionId" to revision))
        val expectedManifest = NormalizationResultCanonicalizer.canonicalize(manifest)
        assertContentEquals(expectedLedger, storage.open(first.ledgerRevisionContent.coreReferenceId).readAllBytes())
        assertContentEquals(expectedManifest,
            storage.open(first.reconciliationManifest.coreReferenceId).readAllBytes())
        assertEquals(sha(expectedLedger), first.ledgerRevisionContent.sha256)
        assertEquals(sha(expectedManifest), first.reconciliationManifest.sha256)
    }

    @Test
    fun identityMatchesContextFixedVectorAndV1RevisionIsIncompatible() {
        val expectedIdentity = map(vector["inputIdentity"])
        val ledgerRef = map(expectedIdentity["ledgerRevisionContent"])
        val manifestRef = map(expectedIdentity["reconciliationManifest"])
        fun artifact(ref: Map<String, Any?>) = AnalyticsInputArtifact(
            string(ref["coreReferenceId"]), string(ref["mediaType"]),
            (ref["byteSize"] as Number).toLong(), string(ref["sha256"]))
        val fixed = PreparedAnalyticsInput(string(expectedIdentity["ledgerRevisionId"]),
            string(expectedIdentity["ledgerContentHash"]), string(manifestRef["manifestHash"]),
            listOf("INITIAL_EXPOSURE"), artifact(ledgerRef), artifact(manifestRef))
        val (identity, hash) = builder.inputIdentity(fixed,
            mapper.writeValueAsString(vector["analysisConfig"]),
            mapper.writeValueAsString(expectedIdentity["targetVersionSet"]))
        assertContentEquals(NormalizationResultCanonicalizer.canonicalize(expectedIdentity),
            NormalizationResultCanonicalizer.canonicalize(identity))
        assertEquals(vector["expectedInputHash"], hash)
        source.version = NormalizationResultVerifier.RESULT_SCHEMA_V1
        assertEquals("ANALYTICS_INPUT_UNAVAILABLE",
            assertFailsWith<TradingFailure> { builder.prepare(revision) }.code)
    }

    private fun map(value: Any?): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return value as Map<String, Any?>
    }
    private fun string(value: Any?) = value as String
    private fun sha(bytes: ByteArray) = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(bytes))

    private class MemoryStore : TradingAnalyticsArtifactStore {
        private val bytes = mutableMapOf<String, ByteArray>()
        override fun put(revisionId: String, role: String, mediaType: String,
                         write: (OutputStream) -> Unit): AnalyticsInputArtifact {
            val out = ByteArrayOutputStream()
            write(out)
            val payload = out.toByteArray()
            val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload))
            val id = "$revisionId:$role:$hash"
            bytes[id] = payload
            return AnalyticsInputArtifact(id, mediaType, payload.size.toLong(), hash)
        }
        override fun open(coreReferenceId: String): InputStream = ByteArrayInputStream(bytes.getValue(coreReferenceId))
        override fun deleteRevision(revisionId: String) { bytes.keys.removeIf { it.startsWith("$revisionId:") } }
    }
}
