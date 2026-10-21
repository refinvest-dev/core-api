package com.refinvest.core.tradingreview.application

import com.refinvest.core.tradingreview.domain.NormalizationRun
import com.refinvest.core.tradingreview.port.NormalizationStagedItem
import com.refinvest.core.tradingreview.port.TradingNormalizationResultStore
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

/** Verification result contains only bounded metadata; collection members stay in staging. */
internal data class VerifiedStagedResult(
    val counts: Map<String, Long>,
    val sectionDigests: List<Map<String, Any>>,
    val manifestHash: String,
    val resultHash: String,
    val manifestBase: Map<String, Any?>,
    val qualityBase: Map<String, Any?>,
    val coverages: List<Any?>,
    val capabilities: List<Any?>,
    val ledgerContentHash: String,
)

/** Reads rows in UTF-16 identity order and feeds JCS bytes into a digest a row at a time. */
internal class NormalizationStagedResultVerifier(
    private val store: TradingNormalizationResultStore,
    private val mapper: ObjectMapper,
    private val batchSize: Int,
) {
    init { require(batchSize > 0) }
    private val sections = NormalizationResultVerifier.sections
    private val collections = sections - setOf("QUALITY_REPORT", "RECONCILIATION_MANIFEST")

    fun verify(base: Map<String, Any?>, run: NormalizationRun? = null,
               logicalJobId: String? = null): VerifiedStagedResult {
        val root = base - NormalizationResultStreamingReader.STAGE_FORMAT_FIELD
        val runId = run?.id ?: root["coreNormalizationRunId"] as? String
            ?: throw ResultIntegrityFailure("RESULT_SCHEMA_UNSUPPORTED")
        if (base[NormalizationResultStreamingReader.STAGE_FORMAT_FIELD] !=
                NormalizationResultStreamingReader.STREAM_STAGE_FORMAT ||
            root.keys != NormalizationResultVerifier.rootFields || root.values.any { it == null } ||
            root["status"] != "COMPLETED" ||
            obj(root["counts"]).keys != NormalizationResultVerifier.countFields ||
            obj(root["resultDigestManifest"]).keys != NormalizationResultVerifier.digestManifestFields ||
            root["resultSchemaVersion"] != NormalizationResultVerifier.RESULT_SCHEMA_V1 ||
            root["resultHashSpecificationVersion"] != NormalizationResultVerifier.RESULT_HASH_V1 ||
            root["hashSpecificationVersion"] != "TRADING_INPUT_SET_HASH_V1")
            throw ResultIntegrityFailure("RESULT_SCHEMA_UNSUPPORTED")
        if (run != null) NormalizationResultVerifier.verifyIdentity(root, run, logicalJobId)

        val receivedManifest = obj(singleton(runId, "RECONCILIATION_MANIFEST"))
        val rootManifest = obj(root["reconciliationManifest"])
        if (!same(rootManifest, receivedManifest) || receivedManifest.keys !=
            NormalizationResultVerifier.manifestFields || receivedManifest.values.any { it == null } ||
            receivedManifest["manifestHashSpecificationVersion"] != NormalizationResultVerifier.RESULT_HASH_V1 ||
            obj(receivedManifest["counts"]).keys != NormalizationResultVerifier.countFields)
            throw ResultIntegrityFailure("RESULT_SCHEMA_UNSUPPORTED")
        val manifestBase = receivedManifest + mapOf(
            "artifactCoverages" to sortedObjects(receivedManifest["artifactCoverages"], "artifactRole"),
            "capability" to sortedStrings(receivedManifest["capability"]))
        val qualityBase = obj(singleton(runId, "QUALITY_REPORT"))
        if (qualityBase["validationIssues"] != emptyList<Any>())
            throw ResultIntegrityFailure("RESULT_SCHEMA_UNSUPPORTED")

        val coverages = smallSection(runId, "ARTIFACT_COVERAGES", 2)
        val capabilities = smallSection(runId, "CAPABILITIES", 64)
        // Hash the received public manifest, including its declared counts. Never substitute
        // actual database counts before this comparison (ADR-066).
        val manifestHash = hashObjectWithSections(manifestBase - "manifestHash", runId,
            mapOf("reviewUnits" to "REVIEW_UNITS",
                "reconciliationMatches" to "RECONCILIATION_MATCHES",
                "reconciliationIssues" to "RECONCILIATION_ISSUES"))
        if (manifestBase["manifestHash"] != manifestHash)
            throw ResultIntegrityFailure("RECONCILIATION_MANIFEST_HASH_MISMATCH")
        if (!same(sortedObjects(manifestBase["artifactCoverages"], "artifactRole"), coverages) ||
            !same(sortedStrings(manifestBase["capability"]), capabilities) ||
            manifestBase["inputSetHash"] != root["inputSetHash"] ||
            manifestBase["versionSetHash"] != obj(root["targetVersionSet"])["versionSetHash"])
            throw ResultIntegrityFailure("RESULT_ITEM_CONFLICT")
        val pinned = run?.inputIdentity?.artifactsByRole
        if (pinned != null) {
            val sourceHashes = obj(manifestBase["sourceArtifactHashesByRole"])
            if (sourceHashes.keys != pinned.keys ||
                pinned.any { (role, artifact) -> sourceHashes[role] != artifact.sha256 } ||
                store.hasInvalidEvidence(run))
                throw ResultIntegrityFailure("EVIDENCE_REFERENCE_INVALID")
        }

        val staged = store.stagedCounts(runId)
        val counts = mapOf(
            "artifactCoverageCount" to count(staged, "ARTIFACT_COVERAGES"),
            "canonicalRecordCount" to count(staged, "CANONICAL_RECORDS"),
            "sourceEvidenceSnapshotCount" to count(staged, "SOURCE_EVIDENCE_SNAPSHOTS"),
            "reviewUnitCount" to count(staged, "REVIEW_UNITS"),
            "positionAllocationCount" to count(staged, "POSITION_ALLOCATIONS"),
            "excludedReviewUnitCount" to count(staged, "EXCLUDED_REVIEW_UNITS"),
            "capabilityCount" to count(staged, "CAPABILITIES"),
            "validationIssueCount" to count(staged, "VALIDATION_ISSUES"),
            "reconciliationMatchCount" to count(staged, "RECONCILIATION_MATCHES"),
            "reconciliationIssueCount" to count(staged, "RECONCILIATION_ISSUES"),
        )
        if (counts["artifactCoverageCount"] != 2L ||
            !same(counts, root["counts"]) || !same(counts, manifestBase["counts"]))
            throw ResultIntegrityFailure("RESULT_COUNT_MISMATCH")

        val sectionDigests = sections.map { section ->
            val (items, hash) = when (section) {
                "RECONCILIATION_MANIFEST" -> 1L to manifestHash
                "QUALITY_REPORT" -> 1L to hashObjectWithSections(qualityBase, runId,
                    mapOf("validationIssues" to "VALIDATION_ISSUES"))
                else -> hashArray(runId, section)
            }
            if (items != (if (section in collections) count(staged, section) else 1L))
                throw ResultIntegrityFailure("RESULT_COUNT_MISMATCH")
            mapOf<String, Any>("sectionType" to section, "itemCount" to items,
                "hashSpecificationVersion" to NormalizationResultVerifier.RESULT_HASH_V1,
                "sectionHash" to hash)
        }
        if (!same(sectionDigests, root["sectionDigests"]))
            throw ResultIntegrityFailure("RESULT_SECTION_HASH_MISMATCH")
        val bySection = sectionDigests.associateBy { it["sectionType"] }
        val ledgerHash = bySection.getValue("CANONICAL_RECORDS").getValue("sectionHash") as String
        if (root["ledgerContentHash"] != ledgerHash ||
            manifestBase["canonicalRecordSetHash"] != ledgerHash ||
            manifestBase["qualityReportHash"] != bySection.getValue("QUALITY_REPORT")["sectionHash"])
            throw ResultIntegrityFailure("RESULT_SECTION_HASH_MISMATCH")

        val digestManifest = mapOf("resultHashSpecificationVersion" to
                NormalizationResultVerifier.RESULT_HASH_V1,
            "resultSchemaVersion" to NormalizationResultVerifier.RESULT_SCHEMA_V1,
            "inputSetHash" to root["inputSetHash"],
            "inputHashSpecificationVersion" to root["hashSpecificationVersion"],
            "targetVersionSet" to root["targetVersionSet"], "counts" to counts,
            "sectionDigests" to sectionDigests, "reconciliationManifestHash" to manifestHash)
        if (!same(digestManifest, root["resultDigestManifest"]))
            throw ResultIntegrityFailure("RESULT_HASH_MISMATCH")
        val resultHash = NormalizationResultCanonicalizer.hash(digestManifest)
        if (root["resultHash"] != resultHash) throw ResultIntegrityFailure("RESULT_HASH_MISMATCH")
        return VerifiedStagedResult(counts, sectionDigests, manifestHash, resultHash,
            manifestBase, qualityBase, coverages, capabilities, ledgerHash)
    }

    private fun singleton(runId: String, section: String): Any {
        var value: Any? = null
        var count = 0
        store.visitItems(runId, section, batchSize) { row ->
            count++
            if (count > 1) throw ResultIntegrityFailure("RESULT_COUNT_MISMATCH")
            value = parseAndCheck(row)
        }
        if (count != 1) throw ResultIntegrityFailure("RESULT_COUNT_MISMATCH")
        return requireNotNull(value)
    }

    private fun smallSection(runId: String, section: String, maximum: Int): List<Any?> {
        val items = ArrayList<Any?>()
        store.visitItems(runId, section, batchSize) { row ->
            if (items.size >= maximum) throw ResultIntegrityFailure("RESULT_COUNT_MISMATCH")
            items.add(project(section, parseAndCheck(row)))
        }
        return items
    }

    private fun hashArray(runId: String, section: String): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        write(digest, "[")
        store.visitItems(runId, section, batchSize) { row ->
            if (count++ > 0) write(digest, ",")
            val value = project(section, parseAndCheck(row))
            write(digest, NormalizationResultCanonicalizer.canonicalize(value))
        }
        write(digest, "]")
        return count to hex(digest)
    }

    private fun hashObjectWithSections(source: Map<String, Any?>, runId: String,
                                       arrays: Map<String, String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        write(digest, "{")
        source.keys.sorted().forEachIndexed { index, key ->
            if (index > 0) write(digest, ",")
            write(digest, NormalizationResultCanonicalizer.canonicalize(key))
            write(digest, ":")
            val section = arrays[key]
            if (section == null) {
                write(digest, NormalizationResultCanonicalizer.canonicalize(
                    NormalizationResultCanonicalizer.normalize(source[key], key)))
            } else {
                write(digest, "[")
                var first = true
                store.visitItems(runId, section, batchSize) { row ->
                    if (!first) write(digest, ",")
                    first = false
                    write(digest, NormalizationResultCanonicalizer.canonicalize(
                        project(section, parseAndCheck(row))))
                }
                write(digest, "]")
            }
        }
        write(digest, "}")
        return hex(digest)
    }

    private fun parseAndCheck(row: NormalizationStagedItem): Any {
        val value = try { mapper.readValue(row.publicJson, Any::class.java) }
            catch (_: Exception) { throw ResultIntegrityFailure("RESULT_SCHEMA_UNSUPPORTED") }
        if (NormalizationResultCanonicalizer.hash(value) != row.contentHash)
            throw ResultIntegrityFailure("RESULT_ITEM_CONFLICT")
        val identity = when (row.sectionType) {
            "QUALITY_REPORT", "RECONCILIATION_MANIFEST" -> "singleton"
            "CAPABILITIES" -> value as? String
            "VALIDATION_ISSUES" -> {
                val issue = obj(value)
                NormalizationResultCanonicalizer.canonicalize(listOf("artifactRole",
                    "sourceRowNumber", "code", "field", "severity").map { issue[it] })
                    .toString(StandardCharsets.UTF_8)
            }
            else -> {
                val field = when (row.sectionType) {
                    "ARTIFACT_COVERAGES" -> "artifactRole"
                    "CANONICAL_RECORDS" -> "recordId"
                    "SOURCE_EVIDENCE_SNAPSHOTS" -> "snapshotId"
                    "REVIEW_UNITS" -> "reviewUnitId"
                    "RECONCILIATION_MATCHES" -> "matchId"
                    "RECONCILIATION_ISSUES" -> "issueId"
                    else -> throw ResultIntegrityFailure("RESULT_SCHEMA_UNSUPPORTED")
                }
                obj(value)[field] as? String
            }
        }
        if (identity.isNullOrBlank() || row.identity != identity)
            throw ResultIntegrityFailure("RESULT_ITEM_CONFLICT")
        return value
    }

    private fun project(section: String, raw: Any): Any? {
        val value = normalizeForStage(section, raw)
        if (section == "CAPABILITIES") return value as? String
            ?: throw ResultIntegrityFailure("RESULT_SCHEMA_UNSUPPORTED")
        val item = obj(value)
        return when (section) {
            "CANONICAL_RECORDS" -> item - "recordHash"
            "SOURCE_EVIDENCE_SNAPSHOTS" -> item - "capturedAt"
            else -> item
        }
    }

    /** Persist the same public ordering used by the semantic hash and publication. */
    fun normalizeForStage(section: String, raw: Any?): Any? {
        val value = NormalizationResultCanonicalizer.normalize(raw)
        if (section == "CAPABILITIES") return value
        val item = obj(value)
        return when (section) {
            "SOURCE_EVIDENCE_SNAPSHOTS" -> item +
                ("allowlistedFields" to sortedObjects(item["allowlistedFields"], "name"))
            "REVIEW_UNITS" -> item + mapOf(
                "allocations" to sortedObjects(item["allocations"], "allocationId"),
                "capability" to sortedStrings(item["capability"]),
                "exclusionReasons" to sortedStrings(item["exclusionReasons"]))
            "RECONCILIATION_MATCHES" -> item + mapOf(
                "candidateReviewUnitIds" to sortedStrings(item["candidateReviewUnitIds"]),
                "evidenceRecordIds" to sortedStrings(item["evidenceRecordIds"]),
                "deltas" to sortedObjects(item["deltas"], "field"))
            "RECONCILIATION_ISSUES" -> item +
                ("evidenceRecordIds" to sortedStrings(item["evidenceRecordIds"]))
            "RECONCILIATION_MANIFEST" -> item + mapOf(
                "artifactCoverages" to sortedObjects(item["artifactCoverages"], "artifactRole"),
                "capability" to sortedStrings(item["capability"]))
            else -> item
        }
    }

    private fun sortedObjects(value: Any?, field: String): List<Map<String, Any?>> {
        val unique = sortedMapOf<String, Map<String, Any?>>()
        for (raw in array(value)) {
            val item = obj(raw)
            val key = (item[field] as? String)?.takeIf(String::isNotBlank)
                ?: throw ResultIntegrityFailure("RESULT_ITEM_CONFLICT")
            if (unique.putIfAbsent(key, item) != null)
                throw ResultIntegrityFailure("RESULT_ITEM_CONFLICT")
        }
        return unique.values.toList()
    }

    private fun sortedStrings(value: Any?): List<String> {
        val items = array(value).map {
            (it as? String)?.takeIf(String::isNotBlank)
                ?: throw ResultIntegrityFailure("RESULT_ITEM_CONFLICT")
        }
        if (items.size != items.toSet().size) throw ResultIntegrityFailure("RESULT_ITEM_CONFLICT")
        return items.sorted()
    }

    private fun obj(value: Any?): Map<String, Any?> {
        val raw = value as? Map<*, *> ?: throw ResultIntegrityFailure("RESULT_SCHEMA_UNSUPPORTED")
        if (raw.keys.any { it !is String }) throw ResultIntegrityFailure("RESULT_SCHEMA_UNSUPPORTED")
        @Suppress("UNCHECKED_CAST")
        return raw as Map<String, Any?>
    }

    private fun array(value: Any?): List<Any?> = value as? List<*>
        ?: throw ResultIntegrityFailure("RESULT_SCHEMA_UNSUPPORTED")

    private fun count(staged: Map<String, Long>, section: String) = staged[section] ?: 0L

    private fun same(left: Any?, right: Any?): Boolean =
        NormalizationResultCanonicalizer.canonicalize(left).contentEquals(
            NormalizationResultCanonicalizer.canonicalize(right))

    private fun write(digest: MessageDigest, value: String) =
        write(digest, value.toByteArray(StandardCharsets.UTF_8))
    private fun write(digest: MessageDigest, value: ByteArray) { digest.update(value) }
    private fun hex(digest: MessageDigest) = HexFormat.of().formatHex(digest.digest())
}
