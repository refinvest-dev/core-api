package com.refinvest.core.tradingreview.port.importsession

import com.refinvest.core.tradingreview.domain.TradingImportSession
import com.refinvest.core.tradingreview.domain.importsession.TradingSourceArtifact
import com.refinvest.core.tradingreview.domain.valueobject.NormalizationRunId
import com.refinvest.core.tradingreview.domain.valueobject.TradingArtifactRetentionStatus
import com.refinvest.core.tradingreview.domain.valueobject.TradingArtifactRole
import com.refinvest.core.tradingreview.domain.valueobject.TradingBookId
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportFailure
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportSessionId
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportStatus
import com.refinvest.core.tradingreview.domain.valueobject.TradingPositionMode
import java.time.Instant

data class TradingImportSessionResult(val id: TradingImportSessionId, val bookId: TradingBookId,
    val sourceTimezone: String, val reviewTimezone: String, val declaredPositionMode: TradingPositionMode,
    val status: TradingImportStatus, val dataPolicyVersion: String, val retentionPolicy: String,
    val maskingPolicyVersion: String, val evidenceRetention: String, val deletionPolicyVersion: String,
    val versionSetJson: String, val createdAt: Instant, val validationStartedAt: Instant?,
    val activeNormalizationRunId: NormalizationRunId?, val failure: TradingImportFailure?,
    val uploadedArtifactRoles: List<TradingArtifactRole>, val missingArtifactRoles: List<TradingArtifactRole>,
    val canValidate: Boolean) {
    companion object {
        fun fromDomain(value: TradingImportSession, artifacts: List<TradingSourceArtifact> = value.artifacts) = TradingImportSessionResult(
            value.id, value.bookId, value.sourceTimezone, value.reviewTimezone, value.declaredPositionMode,
            value.status, value.dataPolicyVersion, value.retentionPolicy, value.maskingPolicyVersion,
            value.evidenceRetention, value.deletionPolicyVersion, value.versionSetJson, value.createdAt,
            value.validationStartedAt, value.activeNormalizationRunId, value.failure,
            artifacts.filter { it.retentionStatus == TradingArtifactRetentionStatus.ACTIVE }.map { it.role }.sortedBy { it.name },
            value.missingArtifactRoles(artifacts).sortedBy { it.name }, value.canValidate(artifacts))
    }
}
