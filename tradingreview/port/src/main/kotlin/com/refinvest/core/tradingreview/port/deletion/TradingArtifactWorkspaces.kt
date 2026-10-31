package com.refinvest.core.tradingreview.port.deletion

import com.refinvest.core.tradingreview.domain.valueobject.*
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant

/** Opaque scope identities; paths and filesystem locking remain in the technology adapter. */
sealed interface TradingArtifactScope {
    data class SessionUploads(val sessionId: TradingImportSessionId) : TradingArtifactScope
    data class RawSlot(val sessionId: TradingImportSessionId, val role: TradingArtifactRole) : TradingArtifactScope
    data class LegacyArtifact(val artifactId: TradingSourceArtifactId) : TradingArtifactScope
    data class Run(val runId: NormalizationRunId) : TradingArtifactScope
    data class Revision(val revisionId: LedgerRevisionId) : TradingArtifactScope
}

interface TradingTemporaryArtifact : AutoCloseable {
    fun openInput(): InputStream
    fun openOutput(): OutputStream
}

interface TradingArtifactLease : AutoCloseable {
    fun checkActive()
    fun createTemporary(): TradingTemporaryArtifact
}

interface TradingArtifactWorkspaces {
    fun acquire(scope: TradingArtifactScope): TradingArtifactLease
    /** Fence new writes, then verify existing handles and all temporary bytes are absent. */
    fun purge(scope: TradingArtifactScope): Boolean
    fun cleanupOrphans(olderThan: Instant, limit: Int): Int
}
