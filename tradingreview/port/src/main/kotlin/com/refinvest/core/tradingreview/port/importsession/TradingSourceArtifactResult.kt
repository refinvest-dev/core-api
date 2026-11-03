package com.refinvest.core.tradingreview.port.importsession

import com.refinvest.core.tradingreview.domain.importsession.TradingSourceArtifact
import com.refinvest.core.tradingreview.domain.valueobject.TradingArtifactRetentionStatus
import com.refinvest.core.tradingreview.domain.valueobject.TradingArtifactRole
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportSessionId
import com.refinvest.core.tradingreview.domain.valueobject.TradingSourceArtifactId
import java.time.Instant

data class TradingSourceArtifactResult(val id: TradingSourceArtifactId, val sessionId: TradingImportSessionId,
    val role: TradingArtifactRole, val filename: String?, val contentType: String, val byteSize: Long,
    val sha256: String, val dialectCandidate: String, val retentionStatus: TradingArtifactRetentionStatus,
    val rawReparseAvailable: Boolean, val rawExpiresAt: Instant, val uploadedAt: Instant, val deletedAt: Instant?) {
    companion object {
        fun fromDomain(value: TradingSourceArtifact, now: Instant) = TradingSourceArtifactResult(value.id, value.sessionId,
            value.role, value.filename, value.contentType, value.byteSize, value.sha256, value.dialectCandidate,
            value.retentionStatus, value.rawReparseAvailable(now), value.rawExpiresAt, value.uploadedAt, value.deletedAt)
    }
}
