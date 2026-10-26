package com.refinvest.core.tradingreview.application

import com.refinvest.core.tradingreview.port.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

private fun unavailable(): Nothing = throw TradingFailure("ANALYTICS_INPUT_UNAVAILABLE", 422)

data class PreparedAnalyticsInput(
    val ledgerRevisionId: String,
    val ledgerContentHash: String,
    val manifestHash: String,
    val capabilitySnapshot: List<String>,
    val ledgerRevisionContent: AnalyticsInputArtifact,
    val reconciliationManifest: AnalyticsInputArtifact,
)

/** Builds immutable JCS entity bytes from one published V2 Revision. */
@Service
open class TradingAnalyticsInputBuilder(
    private val reader: TradingAnalyticsRevisionReader,
    private val artifacts: TradingAnalyticsArtifactStore,
    private val members: TradingMemberProvider,
    private val mapper: ObjectMapper,
) {
    @Transactional
    open fun prepare(revisionId: String): PreparedAnalyticsInput {
        try { UUID.fromString(revisionId) } catch (_: Exception) { unavailable() }
        val header = reader.find(revisionId, members.currentMemberId().value)
            ?: throw TradingFailure("ANALYTICS_INPUT_UNAVAILABLE", 422)
        if (header.resultSchemaVersion != NormalizationResultVerifier.RESULT_SCHEMA_V2)
            throw TradingFailure("ANALYTICS_INPUT_UNAVAILABLE", 422)
        if (!validHash(header.ledgerContentHash) || !validHash(header.manifestHash))
            unavailable()
        val manifest = obj(header.manifestJson)
        if (manifest["manifestHash"] != header.manifestHash ||
            NormalizationResultCanonicalizer.hash(manifest - "manifestHash") != header.manifestHash)
            unavailable()
        val terms = array(header.instrumentTermsJson).map(::obj)
        val instrumentIds = sortedSetOf<String>()
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("[".toByteArray(StandardCharsets.UTF_8))
        var recordCount = 0L
        var previousId: String? = null
        reader.visitRecords(revisionId) { raw ->
            val record = obj(raw)
            val id = string(record["recordId"])
            if (previousId != null && previousId!! >= id) unavailable()
            previousId = id
            instrumentIds.add(string(record["instrumentId"]))
            if (recordCount++ > 0) digest.update(','.code.toByte())
            digest.update(NormalizationResultCanonicalizer.canonicalize(record - "recordHash"))
        }
        digest.update(']'.code.toByte())
        if (recordCount != header.canonicalRecordCount ||
            HexFormat.of().formatHex(digest.digest()) != header.ledgerContentHash)
            unavailable()
        val byId = terms.associateBy { string(it["instrumentId"]) }
        if (byId.size != terms.size || !byId.keys.containsAll(instrumentIds))
            unavailable()
        val descriptors = instrumentIds.map { id ->
            val term = byId.getValue(id)
            val venueSymbol = string(term["venueSymbol"])
            mapOf("instrumentId" to id, "sourceSymbol" to venueSymbol,
                "venueSymbol" to venueSymbol,
                "settlementAsset" to string(term["settlementAsset"]),
                "quantityStep" to string(term["quantityStep"]),
                "priceTick" to string(term["priceTick"]),
                "contractMultiplier" to string(term["contractMultiplier"]),
                "settlementPrecision" to term["settlementPrecision"],
                "instrumentTermsVersion" to string(term["instrumentTermsVersion"]))
        }
        val manifestArtifact = artifacts.put(revisionId, "reconciliationManifest",
            "application/vnd.refinvest.trading-reconciliation-manifest.v1+json") { out ->
            out.write(NormalizationResultCanonicalizer.canonicalize(manifest))
        }
        val ledgerArtifact = artifacts.put(revisionId, "ledgerRevisionContent",
            "application/vnd.refinvest.trading-analysis-input.v1+json") { out ->
            writeLedger(out, header, descriptors)
        }
        val capability = array(header.capabilityJson).map(::string).sorted()
        if (capability.size != capability.toSet().size) unavailable()
        return PreparedAnalyticsInput(revisionId, header.ledgerContentHash, header.manifestHash,
            capability, ledgerArtifact, manifestArtifact)
    }

    private fun writeLedger(out: OutputStream, header: AnalyticsRevisionSource,
                            descriptors: List<Map<String, Any?>>) {
        fun raw(value: String) = out.write(value.toByteArray(StandardCharsets.UTF_8))
        fun value(value: Any?) = out.write(NormalizationResultCanonicalizer.canonicalize(value))
        fun rows(visit: ((String) -> Unit) -> Unit): Long {
            raw("[")
            var count = 0L
            visit { item ->
                if (count++ > 0) raw(",")
                value(obj(item))
            }
            raw("]")
            return count
        }
        raw("{\"canonicalRecords\":")
        if (rows { accept -> reader.visitRecords(header.revisionId, accept) } != header.canonicalRecordCount)
            unavailable()
        raw(",\"canonicalSchemaVersion\":"); value(header.canonicalSchemaVersion)
        raw(",\"instrumentDescriptors\":"); value(descriptors)
        raw(",\"ledgerContentHash\":"); value(header.ledgerContentHash)
        raw(",\"ledgerRevisionId\":"); value(header.revisionId)
        raw(",\"reviewUnitEngineInputs\":")
        if (rows { accept -> reader.visitEngineInputs(header.revisionId, accept) } != header.reviewUnitCount)
            unavailable()
        raw(",\"reviewUnitSchemaVersion\":"); value(header.reviewUnitSchemaVersion)
        raw(",\"schemaVersion\":\"TRADING_ANALYTICS_LEDGER_INPUT_V1\"")
        raw(",\"sourceEvidenceReferences\":")
        if (rows { accept -> reader.visitEvidenceReferences(header.revisionId, accept) } !=
            header.canonicalRecordCount) unavailable()
        raw("}")
    }

    fun inputIdentity(input: PreparedAnalyticsInput, analysisConfigJson: String,
                      analyticsVersionSetJson: String): Pair<Map<String, Any?>, String> {
        val config = obj(analysisConfigJson)
        val versions = obj(analyticsVersionSetJson)
        val identity = mapOf<String, Any?>(
            "hashSpecificationVersion" to "TRADING_ANALYTICS_INPUT_HASH_V1",
            "ledgerRevisionId" to input.ledgerRevisionId,
            "ledgerRevisionContent" to artifactIdentity(input.ledgerRevisionContent),
            "reconciliationManifest" to (artifactIdentity(input.reconciliationManifest) +
                ("manifestHash" to input.manifestHash)),
            "ledgerContentHash" to input.ledgerContentHash,
            "analysisConfigHash" to NormalizationResultCanonicalizer.hash(config),
            "targetVersionSet" to versions,
            "capabilitySnapshot" to input.capabilitySnapshot)
        return identity to NormalizationResultCanonicalizer.hash(identity)
    }

    private fun artifactIdentity(artifact: AnalyticsInputArtifact) = mapOf(
        "coreReferenceId" to artifact.coreReferenceId, "mediaType" to artifact.mediaType,
        "byteSize" to artifact.byteSize, "sha256" to artifact.sha256)

    private fun obj(json: String): Map<String, Any?> = obj(mapper.readValue(json, Any::class.java))
    private fun obj(value: Any?): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return value as? Map<String, Any?> ?: unavailable()
    }
    private fun array(json: String): List<Any?> = mapper.readValue(json, List::class.java) as List<Any?>
    private fun string(value: Any?): String = (value as? String)?.takeIf(String::isNotBlank)
        ?: unavailable()
    private fun validHash(value: String) = Regex("[0-9a-f]{64}").matches(value)
}
