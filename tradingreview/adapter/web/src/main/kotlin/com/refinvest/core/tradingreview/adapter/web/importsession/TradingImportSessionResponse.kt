package com.refinvest.core.tradingreview.adapter.web.importsession

import com.refinvest.core.tradingreview.adapter.web.artifact.TradingSourceArtifactResponse
import com.refinvest.core.tradingreview.domain.valueobject.TradingArtifactRole
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportStatus
import com.refinvest.core.tradingreview.domain.valueobject.TradingPositionMode
import com.refinvest.core.tradingreview.port.inbound.importsession.TradingImportSessionView
import java.time.Instant
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

data class TradingImportSessionResponse(
    val id: String, val tradingBookId: String, val sourceTimezone: String, val reviewTimezone: String,
    val declaredPositionMode: TradingPositionMode, val status: TradingImportStatus,
    val artifacts: List<TradingSourceArtifactResponse>, val dataPolicyVersion: String,
    val retentionPolicy: String, val maskingPolicyVersion: String, val evidenceRetention: String,
    val deletionPolicyVersion: String, val versions: JsonNode, val createdAt: Instant,
    val validationStartedAt: Instant?, val uploadedArtifactRoles: List<TradingArtifactRole>,
    val missingArtifactRoles: List<TradingArtifactRole>, val canValidate: Boolean,
    val failure: TradingImportFailureResponse?, val completedAt: Instant?,
    val qualitySummary: JsonNode? = null, val createdLedgerRevisionId: String? = null,
) {
    companion object {
        fun from(view: TradingImportSessionView, mapper: ObjectMapper): TradingImportSessionResponse {
            val session = view.session
            val uploaded = session.uploadedArtifactRoles
            val missing = session.missingArtifactRoles
            val published = view.published
            val qualitySummary = published?.let {
                val report = mapper.readValue(it.qualityReportJson, Map::class.java)
                    .entries.associate { entry -> entry.key.toString() to entry.value }
                val capabilities = mapper.readValue(it.capabilityJson, List::class.java)
                mapper.readTree(mapper.writeValueAsString(report + ("capability" to capabilities)))
            }
            return TradingImportSessionResponse(session.id.value.toString(), session.bookId.value.toString(),
                session.sourceTimezone, session.reviewTimezone, session.declaredPositionMode, session.status,
                view.artifacts.map { TradingSourceArtifactResponse.from(it) }, session.dataPolicyVersion,
                session.retentionPolicy, session.maskingPolicyVersion, session.evidenceRetention,
                session.deletionPolicyVersion, mapper.readTree(session.versionSetJson), session.createdAt,
                session.validationStartedAt,
                uploaded, missing, session.canValidate, session.failure?.let(TradingImportFailureResponse::from),
                published?.publishedAt ?: session.failure?.occurredAt,
                qualitySummary, published?.revisionId)
        }
    }
}
