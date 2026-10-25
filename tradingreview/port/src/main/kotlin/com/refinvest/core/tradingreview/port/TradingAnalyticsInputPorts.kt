package com.refinvest.core.tradingreview.port

import java.io.OutputStream
import java.io.InputStream

/** A pinned published Revision. The manifest and terms are stored publication values. */
data class AnalyticsRevisionSource(
    val revisionId: String,
    val resultSchemaVersion: String,
    val ledgerContentHash: String,
    val manifestHash: String,
    val canonicalSchemaVersion: String,
    val reviewUnitSchemaVersion: String,
    val instrumentTermsJson: String,
    val manifestJson: String,
    val capabilityJson: String,
    val canonicalRecordCount: Long,
    val reviewUnitCount: Long,
)

interface TradingAnalyticsRevisionReader {
    fun find(revisionId: String, ownerId: Long): AnalyticsRevisionSource?
    fun visitRecords(revisionId: String, accept: (String) -> Unit)
    fun visitEvidenceReferences(revisionId: String, accept: (String) -> Unit)
    fun visitEngineInputs(revisionId: String, accept: (String) -> Unit)
}

data class AnalyticsInputArtifact(
    val coreReferenceId: String,
    val mediaType: String,
    val byteSize: Long,
    val sha256: String,
)

interface TradingAnalyticsArtifactStore {
    /** Publishes exact immutable entity bytes under a deterministic revision/role identity. */
    fun put(revisionId: String, role: String, mediaType: String,
            write: (OutputStream) -> Unit): AnalyticsInputArtifact
    /** Only internal, authorization-checked callers may redeem a stored reference. */
    fun open(coreReferenceId: String): InputStream
    fun deleteRevision(revisionId: String)
}
