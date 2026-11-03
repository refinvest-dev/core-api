package com.refinvest.core.tradingreview.adapter.web.artifact

import com.refinvest.core.tradingreview.domain.valueobject.TradingArtifactRetentionStatus
import com.refinvest.core.tradingreview.domain.valueobject.TradingArtifactRole
import com.refinvest.core.tradingreview.port.inbound.importsession.TradingSourceArtifactResult
import java.time.Instant

data class TradingSourceArtifactResponse(
    val id: String, val importSessionId: String, val role: TradingArtifactRole, val filename: String?,
    val contentType: String, val byteSize: Long, val sha256: String, val dialectCandidate: String,
    val detectedDialect: String?, val schemaFingerprint: String?, val uploadStatus: String,
    val retentionStatus: TradingArtifactRetentionStatus, val rawReparseAvailable: Boolean,
    val coverage: Any?, val rawExpiresAt: Instant, val uploadedAt: Instant, val deletedAt: Instant?,
) {
    companion object {
        fun from(a: TradingSourceArtifactResult) = TradingSourceArtifactResponse(
            a.id.value.toString(), a.sessionId.value.toString(), a.role, a.filename, a.contentType,
            a.byteSize, a.sha256, a.dialectCandidate, null, null, "UPLOADED", a.retentionStatus,
            a.rawReparseAvailable, null, a.rawExpiresAt, a.uploadedAt, a.deletedAt)
    }
}
