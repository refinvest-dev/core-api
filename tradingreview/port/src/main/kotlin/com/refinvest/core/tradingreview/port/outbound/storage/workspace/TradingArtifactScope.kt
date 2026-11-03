package com.refinvest.core.tradingreview.port.outbound.storage.workspace

import com.refinvest.core.tradingreview.domain.valueobject.*
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant

sealed interface TradingArtifactScope {
    data class SessionUploads(val sessionId: TradingImportSessionId) : TradingArtifactScope
    data class RawSlot(val sessionId: TradingImportSessionId, val role: TradingArtifactRole) : TradingArtifactScope
    data class LegacyArtifact(val artifactId: TradingSourceArtifactId) : TradingArtifactScope
    data class Run(val runId: NormalizationRunId) : TradingArtifactScope
    data class Revision(val revisionId: LedgerRevisionId) : TradingArtifactScope
}
