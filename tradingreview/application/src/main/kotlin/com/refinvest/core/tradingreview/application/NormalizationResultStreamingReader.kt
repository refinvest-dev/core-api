package com.refinvest.core.tradingreview.application

import tools.jackson.core.JacksonException
import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.core.StreamReadConstraints
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.exc.StreamConstraintsException
import tools.jackson.core.json.JsonFactory
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.ObjectMapper
import java.io.InputStream
import java.nio.charset.StandardCharsets

/** Reads collection entries after the transport bytes have been authenticated. */
internal data class NormalizationResultReadLimits(
    val maxNestingDepth: Int = 64,
    val maxStringLength: Int = 1_048_576,
    val maxNameLength: Int = 256,
    val maxTokenCount: Long = 5_000_000,
) {
    init {
        require(maxNestingDepth > 0 && maxStringLength > 0 && maxNameLength > 0 && maxTokenCount > 0)
    }
}

internal class NormalizationResultStreamingReader(
    private val mapper: ObjectMapper,
    limits: NormalizationResultReadLimits = NormalizationResultReadLimits(),
) {
    companion object {
        const val STAGE_FORMAT_FIELD = "_coreStageFormat"
        const val STREAM_STAGE_FORMAT = "STREAM_V2"
    }
    private val itemReader = mapper.readerFor(Any::class.java)
        .without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    private val parserFactory = JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder()
            .maxNestingDepth(limits.maxNestingDepth)
            .maxStringLength(limits.maxStringLength)
            .maxNameLength(limits.maxNameLength)
            .maxTokenCount(limits.maxTokenCount)
            .build())
        .build()
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
        parserFactory.createParser(input).use { parser ->
            if (parser.nextToken() != JsonToken.START_OBJECT) malformed()
            val root = linkedMapOf<String, Any?>()
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.PROPERTY_NAME) malformed()
                val name = parser.currentName()
                if (name !in NormalizationResultVerifier.rootFields && name != "analyticsInputSupplement")
                    throw ResultIntegrityFailure("RESULT_SCHEMA_UNSUPPORTED")
                if (parser.nextToken() == null) malformed()
                root[name] = when (name) {
                    in rootCollections -> {
                        val (section, identity) = rootCollections.getValue(name)
                        readItems(parser, section, identity, accept)
                        emptyList<Any>()
                    }
                    "reconciliationManifest" -> readManifest(parser, accept)
                    "analyticsInputSupplement" -> readSupplement(parser, accept)
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
    } catch (_: StreamConstraintsException) {
        // These are local capacity limits, not a source-data rejection.
        throw ResultIntegrityFailure("TEMPORARY_DEPENDENCY_FAILURE")
    } catch (_: JacksonException) {
        throw ResultIntegrityFailure("RESULT_PAYLOAD_PARSE_FAILED")
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

    private fun readSupplement(parser: JsonParser, accept: (String, String, Any) -> Unit): Map<String, Any?> {
        if (parser.currentToken() != JsonToken.START_OBJECT) malformed()
        val supplement = linkedMapOf<String, Any?>()
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.PROPERTY_NAME) malformed()
            val name = parser.currentName()
            if (name !in setOf("schemaVersion", "reviewUnitEngineInputs"))
                throw ResultIntegrityFailure("RESULT_SCHEMA_UNSUPPORTED")
            if (parser.nextToken() == null) malformed()
            supplement[name] = if (name == "reviewUnitEngineInputs") {
                readItems(parser, "ANALYTICS_INPUT_SUPPLEMENT_ROWS", "reviewUnitId", accept)
                emptyList<Any>()
            } else readValue(parser)
        }
        if (supplement.keys != setOf("schemaVersion", "reviewUnitEngineInputs"))
            throw ResultIntegrityFailure("RESULT_SCHEMA_UNSUPPORTED")
        accept("ANALYTICS_INPUT_SUPPLEMENT", "singleton", supplement)
        return supplement
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
