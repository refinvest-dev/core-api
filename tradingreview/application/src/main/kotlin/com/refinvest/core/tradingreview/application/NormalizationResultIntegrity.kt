package com.refinvest.core.tradingreview.application

import com.refinvest.core.tradingreview.domain.NormalizationRun
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.HexFormat
import java.util.Locale

/** ADR-066 hashes the public result DTO. No Compute internal model is an input to this verifier. */
class ResultIntegrityFailure(val code: String) : RuntimeException(code)

object NormalizationResultCanonicalizer {
    private const val MAX_SAFE_INTEGER = 9007199254740991L
    private val DECIMAL_KEYS = setOf("value", "amount", "actual", "expected", "absoluteDelta", "tolerance")
    private val TIME_KEYS = setOf("earliestObservedAt", "latestObservedAt", "occurredAt", "openedAt", "closedAt")

    fun canonicalize(value: Any?): ByteArray = buildString { appendValue(value) }.toByteArray(StandardCharsets.UTF_8)
    fun hash(value: Any?): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(canonicalize(value)))

    /** Decimal and instant normalization applies only to their public DTO property names. */
    fun normalize(value: Any?, key: String = ""): Any? = when {
        key == "allowlistedFields" -> value
        value is Map<*, *> -> value.entries.associate { (name, item) ->
            val field = name as? String ?: throw ResultIntegrityFailure("RESULT_SCHEMA_UNSUPPORTED")
            field to normalize(item, field)
        }
        value is List<*> -> value.map { normalize(it) }
        value is String -> when (key) {
            in DECIMAL_KEYS -> try {
                val number = BigDecimal(value)
                if (number.signum() == 0) "0" else number.stripTrailingZeros().toPlainString()
            } catch (_: Exception) { throw ResultIntegrityFailure("RESULT_SCHEMA_UNSUPPORTED") }
            in TIME_KEYS -> try {
                val instant = OffsetDateTime.parse(value).toInstant()
                val base = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss", Locale.ROOT)
                    .withZone(ZoneOffset.UTC).format(instant)
                val fraction = if (instant.nano == 0) "" else "." +
                    instant.nano.toString().padStart(9, '0').trimEnd('0')
                base + fraction + "Z"
            } catch (_: Exception) { throw ResultIntegrityFailure("RESULT_SCHEMA_UNSUPPORTED") }
            else -> value
        }
        else -> value
    }

    private fun StringBuilder.appendValue(value: Any?) {
        when (value) {
            null -> append("null")
            is String -> appendQuoted(value)
            is Boolean -> append(if (value) "true" else "false")
            is Byte, is Short, is Int, is Long -> {
                val integer = (value as Number).toLong()
                if (integer < 0 || integer > MAX_SAFE_INTEGER) throw ResultIntegrityFailure("RESULT_COUNT_MISMATCH")
                append(integer)
            }
            is Map<*, *> -> {
                append('{')
                value.entries.sortedWith { a, b ->
                    val left = a.key as? String ?: throw ResultIntegrityFailure("RESULT_SCHEMA_UNSUPPORTED")
                    val right = b.key as? String ?: throw ResultIntegrityFailure("RESULT_SCHEMA_UNSUPPORTED")
                    left.compareTo(right) // Kotlin String compares UTF-16 code units, as JCS requires.
                }.forEachIndexed { index, entry ->
                    if (index > 0) append(',')
                    appendQuoted(entry.key as String)
                    append(':')
                    appendValue(entry.value)
                }
                append('}')
            }
            is List<*> -> {
                append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    appendValue(item)
                }
                append(']')
            }
            else -> throw ResultIntegrityFailure("RESULT_SCHEMA_UNSUPPORTED")
        }
    }

    private fun StringBuilder.appendQuoted(value: String) {
        append('"')
        var index = 0
        while (index < value.length) {
            val char = value[index]
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\u000c' -> append("\\f")
                '\r' -> append("\\r")
                else -> when {
                    char < ' ' -> append("\\u%04x".format(Locale.ROOT, char.code))
                    Character.isHighSurrogate(char) -> {
                        if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1]))
                            throw ResultIntegrityFailure("RESULT_SCHEMA_UNSUPPORTED")
                        append(char).append(value[++index])
                    }
                    Character.isLowSurrogate(char) -> throw ResultIntegrityFailure("RESULT_SCHEMA_UNSUPPORTED")
                    else -> append(char)
                }
            }
            index++
        }
        append('"')
    }
}

/** A verified, normalized public result suitable for idempotent staging. */
data class VerifiedNormalizationResult(
    val public: Map<String, Any?>,
    val sectionValues: Map<String, Any>,
    val counts: Map<String, Long>,
    val sectionDigests: List<Map<String, Any>>,
    val manifestHash: String,
    val resultHash: String,
)

object NormalizationResultVerifier {
    const val RESULT_SCHEMA_V1 = "TRADING_NORMALIZATION_RESULT_V1"
    const val RESULT_HASH_V1 = "TRADING_NORMALIZATION_RESULT_HASH_V1"
    const val RESULT_SCHEMA_V2 = "TRADING_NORMALIZATION_RESULT_V2"
    const val RESULT_HASH_V2 = "TRADING_NORMALIZATION_RESULT_HASH_V2"
    internal val sections = listOf("ARTIFACT_COVERAGES", "CANONICAL_RECORDS", "CAPABILITIES",
        "QUALITY_REPORT", "RECONCILIATION_ISSUES", "RECONCILIATION_MANIFEST",
        "RECONCILIATION_MATCHES", "REVIEW_UNITS", "SOURCE_EVIDENCE_SNAPSHOTS")
    internal val sectionsV2 = (sections + "ANALYTICS_INPUT_SUPPLEMENT").sorted()
    internal val rootFields = setOf("jobId", "coreLogicalJobId", "coreNormalizationRunId",
        "coreImportSessionId", "attemptToken", "deletionGeneration", "status", "targetVersionSet",
        "inputSetHash", "hashSpecificationVersion", "resultSchemaVersion",
        "resultHashSpecificationVersion", "counts", "sectionDigests", "resultDigestManifest",
        "artifactCoverages", "canonicalRecords", "sourceEvidenceSnapshots", "reconciliationManifest",
        "capability", "qualityReport", "ledgerContentHash", "resultHash", "createdAt", "terminalAt")
    internal val countFields = setOf("artifactCoverageCount", "canonicalRecordCount",
        "sourceEvidenceSnapshotCount", "reviewUnitCount", "positionAllocationCount",
        "excludedReviewUnitCount", "capabilityCount", "validationIssueCount",
        "reconciliationMatchCount", "reconciliationIssueCount")
    internal val digestManifestFields = setOf("resultHashSpecificationVersion", "resultSchemaVersion",
        "inputSetHash", "inputHashSpecificationVersion", "targetVersionSet", "counts",
        "sectionDigests", "reconciliationManifestHash")
    internal val manifestFields = setOf("manifestVersion", "manifestHashSpecificationVersion",
        "inputSetHash", "executionInputHash", "canonicalRecordSetHash", "qualityReportHash",
        "instrumentTermsHash", "versionSetHash", "sourceArtifactHashesByRole", "counts",
        "manifestHash", "reconstructionPolicyVersion", "reconciliationVersion",
        "artifactCoverages", "reviewUnits", "reconciliationMatches", "reconciliationIssues",
        "capability", "quantityConservationPassed", "cashFlowConservationPassed")

    fun verify(payload: Map<String, Any?>, run: NormalizationRun? = null,
               logicalJobId: String? = null): VerifiedNormalizationResult {
        if (payload.keys != rootFields || payload.values.any { it == null } ||
            payload["status"] != "COMPLETED" || obj(payload["counts"]).keys != countFields ||
            obj(payload["resultDigestManifest"]).keys != digestManifestFields)
            throw ResultIntegrityFailure("RESULT_SCHEMA_UNSUPPORTED")
        if (payload["resultSchemaVersion"] != RESULT_SCHEMA_V1 ||
            payload["resultHashSpecificationVersion"] != RESULT_HASH_V1 ||
            payload["hashSpecificationVersion"] != "TRADING_INPUT_SET_HASH_V1")
            throw ResultIntegrityFailure("RESULT_SCHEMA_UNSUPPORTED")
        if (run != null) verifyIdentity(payload, run, logicalJobId)
        val public = obj(NormalizationResultCanonicalizer.normalize(payload)).toMutableMap()
        val manifest = obj(public["reconciliationManifest"]).toMutableMap()
        val quality = obj(public["qualityReport"]).toMutableMap()
        val coverages = sortedObjects(public["artifactCoverages"], "artifactRole")
        val records = sortedObjects(public["canonicalRecords"], "recordId")
        val evidence = sortedObjects(public["sourceEvidenceSnapshots"], "snapshotId").map { snapshot ->
            snapshot + ("allowlistedFields" to sortedObjects(snapshot["allowlistedFields"], "name"))
        }
        val capabilities = sortedStrings(public["capability"])
        val issues = sortedObjects(quality["validationIssues"], listOf("artifactRole", "sourceRowNumber",
            "code", "field", "severity"))
        quality["validationIssues"] = issues
        manifest["artifactCoverages"] = sortedObjects(manifest["artifactCoverages"], "artifactRole")
        manifest["capability"] = sortedStrings(manifest["capability"])
        val units = sortedObjects(manifest["reviewUnits"], "reviewUnitId").map { unit ->
            unit + mapOf("allocations" to sortedObjects(unit["allocations"], "allocationId"),
                "capability" to sortedStrings(unit["capability"]),
                "exclusionReasons" to sortedStrings(unit["exclusionReasons"]))
        }
        val matches = sortedObjects(manifest["reconciliationMatches"], "matchId").map { match ->
            match + mapOf("candidateReviewUnitIds" to sortedStrings(match["candidateReviewUnitIds"]),
                "evidenceRecordIds" to sortedStrings(match["evidenceRecordIds"]),
                "deltas" to sortedObjects(match["deltas"], "field"))
        }
        val reconciliationIssues = sortedObjects(manifest["reconciliationIssues"], "issueId").map { issue ->
            issue + ("evidenceRecordIds" to sortedStrings(issue["evidenceRecordIds"]))
        }
        manifest["reviewUnits"] = units
        manifest["reconciliationMatches"] = matches
        manifest["reconciliationIssues"] = reconciliationIssues
        if (manifest.keys != manifestFields || manifest.values.any { it == null } ||
            manifest["manifestHashSpecificationVersion"] != RESULT_HASH_V1 ||
            obj(manifest["counts"]).keys != countFields)
            throw ResultIntegrityFailure("RESULT_SCHEMA_UNSUPPORTED")
        // Hash the received public manifest, including its declared counts. Actual counts are
        // checked independently below; substituting them here would hide a forged manifest.
        val manifestHash = NormalizationResultCanonicalizer.hash(manifest - "manifestHash")
        if (manifest["manifestHash"] != manifestHash)
            throw ResultIntegrityFailure("RECONCILIATION_MANIFEST_HASH_MISMATCH")
        public["artifactCoverages"] = coverages
        public["canonicalRecords"] = records
        public["sourceEvidenceSnapshots"] = evidence
        public["capability"] = capabilities
        public["qualityReport"] = quality
        public["reconciliationManifest"] = manifest
        same(manifest["artifactCoverages"], coverages, "RESULT_ITEM_CONFLICT")
        same(manifest["capability"], capabilities, "RESULT_ITEM_CONFLICT")
        if (manifest["inputSetHash"] != public["inputSetHash"] ||
            manifest["versionSetHash"] != obj(public["targetVersionSet"])["versionSetHash"])
            throw ResultIntegrityFailure("RESULT_ITEM_CONFLICT")
        verifyEvidence(records, evidence, units, matches, reconciliationIssues, manifest, run)
        val allocations = units.flatMap { array(it["allocations"]) }
        val allocationIds = allocations.map { obj(it)["allocationId"] }
        if (allocationIds.size != allocationIds.toSet().size) throw ResultIntegrityFailure("RESULT_ITEM_CONFLICT")
        val counts = mapOf(
            "artifactCoverageCount" to coverages.size.toLong(),
            "canonicalRecordCount" to records.size.toLong(),
            "sourceEvidenceSnapshotCount" to evidence.size.toLong(),
            "reviewUnitCount" to units.size.toLong(),
            "positionAllocationCount" to allocations.size.toLong(),
            "excludedReviewUnitCount" to units.count { array(it["exclusionReasons"]).isNotEmpty() }.toLong(),
            "capabilityCount" to capabilities.size.toLong(),
            "validationIssueCount" to issues.size.toLong(),
            "reconciliationMatchCount" to matches.size.toLong(),
            "reconciliationIssueCount" to reconciliationIssues.size.toLong())
        if (coverages.size != 2) throw ResultIntegrityFailure("RESULT_COUNT_MISMATCH")
        same(counts, public["counts"], "RESULT_COUNT_MISMATCH")
        same(counts, manifest["counts"], "RESULT_COUNT_MISMATCH")
        val sectionValues = mapOf<String, Any>(
            "ARTIFACT_COVERAGES" to coverages,
            "CANONICAL_RECORDS" to records.map { it - "recordHash" },
            "SOURCE_EVIDENCE_SNAPSHOTS" to evidence.map { it - "capturedAt" },
            "REVIEW_UNITS" to units,
            "CAPABILITIES" to capabilities,
            "QUALITY_REPORT" to quality,
            "RECONCILIATION_MATCHES" to matches,
            "RECONCILIATION_ISSUES" to reconciliationIssues,
            "RECONCILIATION_MANIFEST" to (manifest - "manifestHash"))
        val digests = sections.map { section ->
            val value = sectionValues.getValue(section)
            val count = if (section in setOf("QUALITY_REPORT", "RECONCILIATION_MANIFEST")) 1
                else array(value).size
            mapOf<String, Any>("sectionType" to section, "itemCount" to count,
                "hashSpecificationVersion" to RESULT_HASH_V1,
                "sectionHash" to NormalizationResultCanonicalizer.hash(value))
        }
        same(digests, public["sectionDigests"], "RESULT_SECTION_HASH_MISMATCH")
        val bySection = digests.associateBy { it["sectionType"] }
        if (public["ledgerContentHash"] != bySection.getValue("CANONICAL_RECORDS")["sectionHash"] ||
            manifest["canonicalRecordSetHash"] != bySection.getValue("CANONICAL_RECORDS")["sectionHash"] ||
            manifest["qualityReportHash"] != bySection.getValue("QUALITY_REPORT")["sectionHash"])
            throw ResultIntegrityFailure("RESULT_SECTION_HASH_MISMATCH")
        if (bySection.getValue("RECONCILIATION_MANIFEST")["sectionHash"] != manifestHash)
            throw ResultIntegrityFailure("RECONCILIATION_MANIFEST_HASH_MISMATCH")
        val digestManifest = mapOf("resultHashSpecificationVersion" to RESULT_HASH_V1,
            "resultSchemaVersion" to RESULT_SCHEMA_V1, "inputSetHash" to public["inputSetHash"],
            "inputHashSpecificationVersion" to public["hashSpecificationVersion"],
            "targetVersionSet" to public["targetVersionSet"], "counts" to counts,
            "sectionDigests" to digests, "reconciliationManifestHash" to manifestHash)
        same(digestManifest, public["resultDigestManifest"], "RESULT_HASH_MISMATCH")
        val resultHash = NormalizationResultCanonicalizer.hash(digestManifest)
        if (public["resultHash"] != resultHash) throw ResultIntegrityFailure("RESULT_HASH_MISMATCH")
        return VerifiedNormalizationResult(public, sectionValues, counts, digests, manifestHash, resultHash)
    }

    internal fun verifyIdentity(payload: Map<String, Any?>, run: NormalizationRun, logicalJobId: String?) {
        val identity = run.inputIdentity ?: throw ResultIntegrityFailure("RESULT_SCHEMA_UNSUPPORTED")
        if (payload["coreNormalizationRunId"] != run.id || payload["jobId"] != run.computeJobId ||
            (logicalJobId != null && payload["coreLogicalJobId"] != logicalJobId) ||
            payload["coreImportSessionId"] != run.input.importSessionId.value.toString() ||
            payload["attemptToken"] != run.attemptToken ||
            (payload["deletionGeneration"] as? Number)?.toLong() != run.input.deletionGeneration ||
            payload["inputSetHash"] != run.inputFingerprint ||
            payload["hashSpecificationVersion"] != identity.hashSpecificationVersion)
            throw ResultIntegrityFailure("INPUT_SET_HASH_MISMATCH")
        same(identity.targetVersionSet.toWireValue(), payload["targetVersionSet"], "INPUT_SET_HASH_MISMATCH")
    }

    private fun verifyEvidence(records: List<Map<String, Any?>>, evidence: List<Map<String, Any?>>,
                               units: List<Map<String, Any?>>, matches: List<Map<String, Any?>>,
                               issues: List<Map<String, Any?>>, manifest: Map<String, Any?>,
                               run: NormalizationRun?) {
        val recordIds = records.map { it["recordId"] }.toSet()
        val snapshotIds = evidence.map { it["snapshotId"] }.toSet()
        val unitIds = units.map { it["reviewUnitId"] }.toSet()
        val sourceHashes = obj(manifest["sourceArtifactHashesByRole"])
        val pinned = run?.inputIdentity?.artifactsByRole
        for (snapshot in evidence) {
            val role = snapshot["artifactRole"] as? String
            if (role !in sourceHashes || (pinned != null &&
                snapshot["coreArtifactId"] != pinned[role]?.artifactId) ||
                (snapshot["sourceRowNumber"] as? Number)?.toLong()?.let { it > 0 } != true ||
                !sha256(snapshot["sourceRowHash"]))
                throw ResultIntegrityFailure("EVIDENCE_REFERENCE_INVALID")
        }
        if (pinned != null && (sourceHashes.keys != pinned.keys ||
            pinned.any { (role, artifact) -> sourceHashes[role] != artifact.sha256 }))
            throw ResultIntegrityFailure("EVIDENCE_REFERENCE_INVALID")
        for (record in records) if (record["sourceEvidenceSnapshotId"] !in snapshotIds ||
            !sha256(record["recordHash"])) throw ResultIntegrityFailure("EVIDENCE_REFERENCE_INVALID")
        for (unit in units) for (raw in array(unit["allocations"])) {
            val allocation = obj(raw)
            if (allocation["recordId"] !in recordIds || allocation["reviewUnitId"] != unit["reviewUnitId"])
                throw ResultIntegrityFailure("EVIDENCE_REFERENCE_INVALID")
        }
        for (match in matches) {
            if (match["summaryRecordId"] !in recordIds ||
                (match["reviewUnitId"] != null && match["reviewUnitId"] !in unitIds) ||
                array(match["candidateReviewUnitIds"]).any { it !in unitIds } ||
                array(match["evidenceRecordIds"]).any { it !in recordIds })
                throw ResultIntegrityFailure("EVIDENCE_REFERENCE_INVALID")
        }
        for (issue in issues) {
            if ((issue["reviewUnitId"] != null && issue["reviewUnitId"] !in unitIds) ||
                (issue["summaryRecordId"] != null && issue["summaryRecordId"] !in recordIds) ||
                array(issue["evidenceRecordIds"]).any { it !in recordIds })
                throw ResultIntegrityFailure("EVIDENCE_REFERENCE_INVALID")
        }
    }

    private fun sortedObjects(value: Any?, identity: String): List<Map<String, Any?>> =
        sortedObjects(value, listOf(identity))

    private fun sortedObjects(value: Any?, identity: List<String>): List<Map<String, Any?>> {
        val unique = sortedMapOf<String, Map<String, Any?>>()
        for (raw in array(value)) {
            val item = obj(raw)
            val key = if (identity.size == 1) {
                (item[identity.single()] as? String)?.takeIf(String::isNotBlank)
                    ?: throw ResultIntegrityFailure("RESULT_ITEM_CONFLICT")
            } else NormalizationResultCanonicalizer.canonicalize(identity.map { item[it] })
                .toString(StandardCharsets.UTF_8)
            val old = unique.putIfAbsent(key, item)
            if (old != null) throw ResultIntegrityFailure("RESULT_ITEM_CONFLICT")
        }
        return unique.values.toList()
    }

    private fun sortedStrings(value: Any?): List<String> = array(value).map {
        (it as? String)?.takeIf(String::isNotBlank) ?: throw ResultIntegrityFailure("RESULT_ITEM_CONFLICT")
    }.also { if (it.size != it.toSet().size) throw ResultIntegrityFailure("RESULT_ITEM_CONFLICT") }
        .sorted()

    private fun same(expected: Any?, actual: Any?, code: String) {
        if (!equal(expected, actual)) throw ResultIntegrityFailure(code)
    }

    private fun equal(left: Any?, right: Any?): Boolean =
        NormalizationResultCanonicalizer.canonicalize(left).contentEquals(
            NormalizationResultCanonicalizer.canonicalize(right))

    private fun obj(value: Any?): Map<String, Any?> {
        val raw = value as? Map<*, *> ?: throw ResultIntegrityFailure("RESULT_SCHEMA_UNSUPPORTED")
        if (raw.keys.any { it !is String }) throw ResultIntegrityFailure("RESULT_SCHEMA_UNSUPPORTED")
        @Suppress("UNCHECKED_CAST")
        return raw as Map<String, Any?>
    }

    private fun array(value: Any?): List<Any?> = value as? List<*>
        ?: throw ResultIntegrityFailure("RESULT_SCHEMA_UNSUPPORTED")

    private fun sha256(value: Any?): Boolean = value is String && Regex("[0-9a-f]{64}").matches(value)
}
