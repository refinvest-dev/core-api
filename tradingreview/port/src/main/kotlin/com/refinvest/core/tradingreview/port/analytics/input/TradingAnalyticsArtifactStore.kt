package com.refinvest.core.tradingreview.port.analytics.input

import java.io.OutputStream
import java.io.InputStream

interface TradingAnalyticsArtifactStore {
    /** Publishes exact immutable entity bytes under a deterministic revision/role identity. */
    fun put(revisionId: String, role: String, mediaType: String,
            write: (OutputStream) -> Unit): AnalyticsInputArtifact
    /** Only internal, authorization-checked callers may redeem a stored reference. */
    fun open(coreReferenceId: String): InputStream
    fun purgeRevision(revisionId: String): Boolean
    fun deleteRevision(revisionId: String) { check(purgeRevision(revisionId)) { "Revision files are still in use" } }
}
