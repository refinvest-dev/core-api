package com.refinvest.core.tradingreview.domain.valueobject

import java.util.Collections

class LedgerRevisionResult(
    val versionPin: NormalizationResultVersionPin,
    val resultHash: String,
    val ledgerContentHash: String,
    val reconciliationManifestHash: String,
    counts: Map<String, Long>,
    sectionDigests: List<LedgerSectionDigest>,
) {
    val counts: Map<String, Long> = Collections.unmodifiableMap(LinkedHashMap(counts))
    val sectionDigests: List<LedgerSectionDigest> = Collections.unmodifiableList(sectionDigests.toList())
    init {
        require(listOf(resultHash, ledgerContentHash, reconciliationManifestHash).all { Regex("[0-9a-f]{64}").matches(it) })
        require(this.counts.keys == COUNT_SECTIONS.keys && this.counts.values.all { it >= 0 })
        require(this.counts.getValue("artifactCoverageCount") == 2L)
        require(this.counts.getValue("excludedReviewUnitCount") <= this.counts.getValue("reviewUnitCount"))
        val bySection = this.sectionDigests.associateBy { it.sectionType }
        val expected = COUNT_SECTIONS.values.filterNotNull().toSet() + setOf("QUALITY_REPORT", "RECONCILIATION_MANIFEST") +
            if (versionPin == NormalizationResultVersionPin.V2) setOf("ANALYTICS_INPUT_SUPPLEMENT") else emptySet()
        require(bySection.size == this.sectionDigests.size && bySection.keys == expected)
        require(this.sectionDigests.all { it.hashSpecificationVersion == versionPin.hashSpecificationVersion })
        COUNT_SECTIONS.forEach { (count, section) ->
            if (section != null) require(bySection.getValue(section).itemCount == this.counts.getValue(count))
        }
        require(bySection.getValue("QUALITY_REPORT").itemCount == 1L && bySection.getValue("RECONCILIATION_MANIFEST").itemCount == 1L)
        require(bySection["ANALYTICS_INPUT_SUPPLEMENT"]?.itemCount?.let { it == 1L } != false)
        require(bySection.getValue("CANONICAL_RECORDS").sectionHash == ledgerContentHash &&
            bySection.getValue("RECONCILIATION_MANIFEST").sectionHash == reconciliationManifestHash)
    }
    companion object {
        val COUNT_SECTIONS: Map<String, String?> = Collections.unmodifiableMap(linkedMapOf(
            "artifactCoverageCount" to "ARTIFACT_COVERAGES", "canonicalRecordCount" to "CANONICAL_RECORDS",
            "sourceEvidenceSnapshotCount" to "SOURCE_EVIDENCE_SNAPSHOTS", "reviewUnitCount" to "REVIEW_UNITS",
            "positionAllocationCount" to null, "excludedReviewUnitCount" to null,
            "capabilityCount" to "CAPABILITIES", "validationIssueCount" to null,
            "reconciliationMatchCount" to "RECONCILIATION_MATCHES", "reconciliationIssueCount" to "RECONCILIATION_ISSUES"))
    }
}
