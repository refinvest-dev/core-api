package com.refinvest.core.tradingreview.adapter.persistence

import java.time.Instant

/** Manifest/quality collection arrays have already been stripped by SQL before JDBC reads this row. */
internal data class LedgerRevisionRecord(
    val id: String, val runId: String, val bookId: Long, val importSessionId: Long,
    val ownerId: Long, val revisionNumber: Long, val inputSetHash: String,
    val inputHashSpecificationVersion: String, val inputIdentityJson: String,
    val targetVersionSetJson: String, val resultSchemaVersion: String,
    val resultHashSpecificationVersion: String, val resultHash: String,
    val ledgerContentHash: String, val manifestHash: String, val manifestBaseJson: String,
    val coverageJson: String, val capabilityJson: String, val qualityBaseJson: String,
    val countsJson: String, val sectionDigestsJson: String, val publishedAt: Instant,
)
