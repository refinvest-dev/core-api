package com.refinvest.core.tradingreview.application

import com.refinvest.core.tradingreview.port.NormalizationStagedItem
import tools.jackson.core.JacksonException
import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.ObjectMapper
import java.io.InputStream
import java.nio.charset.StandardCharsets

/** Reads collection entries after the transport bytes have been authenticated. */
internal class NormalizationResultStreamingReader(private val mapper: ObjectMapper) {
    companion object {
        const val STAGE_FORMAT_FIELD = "_coreStageFormat"
        const val STREAM_STAGE_FORMAT = "STREAM_V1"
    }
    private val itemReader = mapper.readerFor(Any::class.java)
        .without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    private val rootCollections = mapOf(
        "artifactCoverages" to ("ARTIFACT_COVERAGES" to "artifactRole"),
        "canonicalRecords" to ("CANONICAL_RECORDS" to "recordId"),
        "sourceEvidenceSnapshots" to ("SOURCE_EVIDENCE_SNAPSHOTS" to "snapshotId"),
        "capability" to ("CAPABILITIES" to null),
    )
    private val manifestCollections = mapOf(
        "reviewUnits" to ("REVIEW_UNITS" to "reviewUnitId"),
        "reconciliationMatches" to ("RECONCILIATION_MATCHES" to "matchId"),
        "reconciliationIssues" to ("RECONCILIATION_ISSUES" to "issueId"),
    )
    private val issueIdentityFields = listOf("artifactRole", "sourceRowNumber", "code", "field", "severity")

    fun read(input: InputStream, accept: (String, String, Any) -> Unit): Map<String, Any?> = try {
        mapper.createParser(input).use { parser ->
            if (parser.nextToken() != JsonToken.START_OBJECT) malformed()
            val root = linkedMapOf<String, Any?>()
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.PROPERTY_NAME) malformed()
                val name = parser.currentName()
                if (name !in NormalizationResultVerifier.rootFields)
                    throw ResultIntegrityFailure("RESULT_SCHEMA_UNSUPPORTED")
                if (parser.nextToken() == null) malformed()
                root[name] = when (name) {
                    in rootCollections -> {
                        val (section, identity) = rootCollections.getValue(name)
                        readItems(parser, section, identity, accept)
                        emptyList<Any>()
                    }
                    "reconciliationManifest" -> readManifest(parser, accept)
                    "qualityReport" -> readQualityReport(parser, accept).also { value ->
                        accept("QUALITY_REPORT", "singleton", value)
                    }.let { emptyMap<String, Any>() }
                    else -> readValue(parser)
                }
            }
            if (parser.nextToken() != null) malformed()
            root[STAGE_FORMAT_FIELD] = STREAM_STAGE_FORMAT
            root
        }
    } catch (_: JacksonException) {
        throw ResultIntegrityFailure("RESULT_PAYLOAD_PARSE_FAILED")
    }

    @Suppress("UNCHECKED_CAST")
    fun rehydrate(base: Map<String, Any?>, staged: List<NormalizationStagedItem>): Map<String, Any?> {
        val grouped = staged.groupBy { it.sectionType }.mapValues { (_, values) -> values.map {
            mapper.readValue(it.publicJson, Any::class.java)
        } }
        val root = base.toMutableMap()
        val streamed = when (root.remove(STAGE_FORMAT_FIELD)) {
            STREAM_STAGE_FORMAT -> true
            null -> false
            else -> throw ResultIntegrityFailure("RESULT_ITEM_CONFLICT")
        }
        val manifest = (base["reconciliationManifest"] as Map<String, Any?>).toMutableMap()
        val mappings = mapOf("ARTIFACT_COVERAGES" to "artifactCoverages",
            "CANONICAL_RECORDS" to "canonicalRecords",
            "SOURCE_EVIDENCE_SNAPSHOTS" to "sourceEvidenceSnapshots")
        for ((section, name) in mappings) root[name] = grouped[section] ?: emptyList<Any>()
        root["capability"] = grouped["CAPABILITIES"] ?: emptyList<Any>()
        val qualityBase = grouped["QUALITY_REPORT"]?.singleOrNull() as? Map<*, *>
            ?: throw ResultIntegrityFailure("RESULT_COUNT_MISMATCH")
        root["qualityReport"] = if (streamed) qualityBase +
            ("validationIssues" to (grouped["VALIDATION_ISSUES"] ?: emptyList<Any>()))
            else qualityBase
        val manifestBase = grouped["RECONCILIATION_MANIFEST"]?.singleOrNull()
            as? Map<String, Any?> ?: throw ResultIntegrityFailure("RESULT_COUNT_MISMATCH")
        manifest.putAll(manifestBase)
        // The previous stage format deliberately omitted these two redundant manifest arrays.
        // Its full payload was verified before staging, so reconstruct them from the staged root.
        // A streamed payload retains the received arrays and must compare them unchanged.
        if (!streamed) {
            manifest["artifactCoverages"] = root["artifactCoverages"]
            manifest["capability"] = root["capability"]
        }
        manifest["reviewUnits"] = grouped["REVIEW_UNITS"] ?: emptyList<Any>()
        manifest["reconciliationMatches"] = grouped["RECONCILIATION_MATCHES"] ?: emptyList<Any>()
        manifest["reconciliationIssues"] = grouped["RECONCILIATION_ISSUES"] ?: emptyList<Any>()
        root["reconciliationManifest"] = manifest
        return root
    }

    private fun readManifest(parser: JsonParser, accept: (String, String, Any) -> Unit): Map<String, Any?> {
        if (parser.currentToken() != JsonToken.START_OBJECT) malformed()
        val manifest = linkedMapOf<String, Any?>()
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.PROPERTY_NAME) malformed()
            val name = parser.currentName()
            if (name !in NormalizationResultVerifier.manifestFields)
                throw ResultIntegrityFailure("RESULT_SCHEMA_UNSUPPORTED")
            if (parser.nextToken() == null) malformed()
            manifest[name] = when (name) {
                in manifestCollections -> {
                    val (section, identity) = manifestCollections.getValue(name)
                    readItems(parser, section, identity, accept)
                    emptyList<Any>()
                }
                // These collections are bounded by role and capability enum cardinality. Keep
                // their received values so the verifier can compare them with the top-level DTO.
                "artifactCoverages" -> readSmallItems(parser, 2)
                "capability" -> readSmallItems(parser, 64)
                else -> readValue(parser)
            }
        }
        accept("RECONCILIATION_MANIFEST", "singleton", manifest)
        return manifest
    }

    private fun readQualityReport(parser: JsonParser, accept: (String, String, Any) -> Unit):
        Map<String, Any?> {
        if (parser.currentToken() != JsonToken.START_OBJECT) malformed()
        val quality = linkedMapOf<String, Any?>()
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.PROPERTY_NAME) malformed()
            val name = parser.currentName()
            if (parser.nextToken() == null) malformed()
            quality[name] = if (name == "validationIssues") {
                readItems(parser, "VALIDATION_ISSUES", "@issue", accept)
                emptyList<Any>()
            } else readValue(parser)
        }
        return quality
    }

    private fun readItems(parser: JsonParser, section: String?, identity: String?,
                          accept: (String, String, Any) -> Unit) {
        if (parser.currentToken() != JsonToken.START_ARRAY) malformed()
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (parser.currentToken() == null) malformed()
            val value = readValue(parser)
            if (section != null) {
                val id = when (identity) {
                    null -> value as? String
                    "@issue" -> (value as? Map<*, *>)?.let { issue ->
                        NormalizationResultCanonicalizer.canonicalize(
                            issueIdentityFields.map { issue[it] }).toString(StandardCharsets.UTF_8)
                    }
                    else -> (value as? Map<*, *>)?.get(identity) as? String
                }
                if (id.isNullOrBlank()) throw ResultIntegrityFailure("RESULT_ITEM_CONFLICT")
                accept(section, id, value)
            }
        }
    }

    private fun readSmallItems(parser: JsonParser, maximum: Int): List<Any> {
        if (parser.currentToken() != JsonToken.START_ARRAY) malformed()
        val values = ArrayList<Any>(maximum)
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (parser.currentToken() == null) malformed()
            if (values.size == maximum) throw ResultIntegrityFailure("RESULT_COUNT_MISMATCH")
            values.add(readValue(parser))
        }
        return values
    }

    private fun readValue(parser: JsonParser): Any =
        itemReader.readValue<Any>(parser) ?: malformed()

    private fun malformed(): Nothing = throw ResultIntegrityFailure("RESULT_PAYLOAD_PARSE_FAILED")
}
