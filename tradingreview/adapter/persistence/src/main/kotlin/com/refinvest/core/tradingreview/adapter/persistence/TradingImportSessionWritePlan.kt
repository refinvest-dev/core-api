package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.tradingreview.domain.TradingImportSession

internal data class TradingImportSessionWritePlan(
    val after: TradingImportSessionRecord,
    val added: List<TradingSourceArtifactRecord>,
    val changed: List<TradingSourceArtifactRecord>,
) {
    companion object {
        fun prepare(current: TradingImportSession, session: TradingImportSession,
                    storedArtifacts: List<TradingSourceArtifactRecord>): TradingImportSessionWritePlan? {
            val before = TradingImportSessionPersistenceMapper.toRecord(current)
            val after = TradingImportSessionPersistenceMapper.toRecord(session)
            if (before.version != after.version || before.deletionPending || after.updatedAt < before.updatedAt) return null
            val rejectedNow = before.status == "VALIDATING" && after.status == "REJECTED"
            val expiredNow = before.rawExpiredAt == null && after.rawExpiredAt != null
            if (before.rejectedAt != after.rejectedAt && !(rejectedNow && after.rejectedAt == after.updatedAt)) return null
            if (before.rawExpiredAt != after.rawExpiredAt && !(expiredNow && after.rawExpiredAt == after.updatedAt &&
                    before.status in setOf("RECEIVED", "VALIDATING", "PROCESSING_FAILED") &&
                    after.status == "PROCESSING_FAILED" && after.failure?.code == "SOURCE_ARTIFACT_EXPIRED" &&
                    after.failure.retryable == false && after.failure.userAction == "REUPLOAD_REQUIRED" &&
                    current.artifacts.any { !after.updatedAt.isBefore(it.rawExpiresAt) })) return null
            if (before.copy(status = after.status, deletionGeneration = after.deletionGeneration,
                    deletionPending = after.deletionPending, updatedAt = after.updatedAt,
                    validationStartedAt = after.validationStartedAt, activeNormalizationRunId = after.activeNormalizationRunId,
                    failure = after.failure, rejectedAt = after.rejectedAt, rawExpiredAt = after.rawExpiredAt,
                    artifacts = emptyList(), normalizationRun = null) != after.copy(artifacts = emptyList(), normalizationRun = null)) return null
            val generationAdvanced = before.deletionGeneration < Long.MAX_VALUE && after.deletionGeneration == before.deletionGeneration + 1
            val stateAllowed = when {
                expiredNow -> generationAdvanced && !after.deletionPending &&
                    after.activeNormalizationRunId == before.activeNormalizationRunId && after.validationStartedAt == before.validationStartedAt
                after.deletionPending -> generationAdvanced && after.status == before.status &&
                    after.activeNormalizationRunId == before.activeNormalizationRunId &&
                    after.validationStartedAt == before.validationStartedAt && after.failure == before.failure
                generationAdvanced -> after.status == (if (before.status == "VALIDATING") "PROCESSING_FAILED" else before.status) &&
                    after.activeNormalizationRunId == before.activeNormalizationRunId && after.validationStartedAt == before.validationStartedAt
                after.deletionGeneration != before.deletionGeneration -> false
                before.status == after.status -> after.activeNormalizationRunId == before.activeNormalizationRunId &&
                    after.validationStartedAt == before.validationStartedAt && after.failure == before.failure
                after.status == "VALIDATING" -> false // New run admission owns this transition.
                before.status == "VALIDATING" -> after.status in setOf("REJECTED", "PROCESSING_FAILED") &&
                    before.activeNormalizationRunId == after.activeNormalizationRunId && before.validationStartedAt == after.validationStartedAt
                else -> false
            }
            if (!stateAllowed) return null
            if (storedArtifacts.size > after.artifacts.size) return null
            for (stored in storedArtifacts) {
                val next = after.artifacts.singleOrNull { it.id == stored.id } ?: return null
                if (!sameArtifact(stored, next)) {
                    val deleting = generationAdvanced && stored.retentionStatus in setOf("ACTIVE", "RETENTION_SCHEDULED") &&
                        next.retentionStatus == "DELETION_PENDING" && sameArtifact(stored.copy(retentionStatus = next.retentionStatus), next)
                    val rejecting = rejectedNow && stored.retentionStatus == "ACTIVE" && stored.rawExpiresAt > after.updatedAt &&
                        next.rawExpiresAt == after.updatedAt.plusSeconds(86400) && sameArtifact(stored.copy(rawExpiresAt = next.rawExpiresAt), next)
                    if (!deleting && !rejecting) return null
                }
            }
            val added = after.artifacts.filter { next -> storedArtifacts.none { it.id == next.id } }
            if (added.isNotEmpty() && (before.status != "RECEIVED" || after.status != "RECEIVED" || generationAdvanced ||
                    added.any { it.version != 0L || it.retentionStatus != "ACTIVE" || it.sessionId != after.id })) return null
            val pendingTransitions = storedArtifacts.count { stored ->
                stored.retentionStatus != "DELETION_PENDING" && after.artifacts.single { it.id == stored.id }.retentionStatus == "DELETION_PENDING"
            }
            if (generationAdvanced && !after.deletionPending && pendingTransitions != 1 && !(expiredNow && pendingTransitions == 0)) return null
            if (after.deletionPending && after.artifacts.any { it.retentionStatus in setOf("ACTIVE", "RETENTION_SCHEDULED") }) return null
            return TradingImportSessionWritePlan(after, added, storedArtifacts.mapNotNull { stored ->
                after.artifacts.single { it.id == stored.id }.takeIf {
                    it.retentionStatus != stored.retentionStatus || it.rawExpiresAt != stored.rawExpiresAt
                }
            })
        }
        private fun sameArtifact(left: TradingSourceArtifactRecord, right: TradingSourceArtifactRecord) =
            left.copy(wrappedDek = null) == right.copy(wrappedDek = null) &&
                java.util.Arrays.equals(left.wrappedDek, right.wrappedDek)
    }
}
