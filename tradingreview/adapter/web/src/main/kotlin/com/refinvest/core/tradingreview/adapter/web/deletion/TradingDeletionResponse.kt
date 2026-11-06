package com.refinvest.core.tradingreview.adapter.web.deletion

import com.refinvest.core.tradingreview.port.inbound.deletion.PurgeComponentStatus
import com.refinvest.core.tradingreview.port.inbound.deletion.TradingDeletion
import java.time.Instant

data class TradingDeletionResponse(val id: String, val targetType: String, val targetId: String,
                                   val requestedScope: String, val state: String,
                                   val affectedResources: AffectedResourceSummary, val revokedAt: Instant,
                                   val primaryPurgeStatus: String, val objectPurgeStatus: String,
                                   val computeRuntimePurgeStatus: String, val backupPurgeStatus: String,
                                   val backupPurgeDueAt: Instant?, val retryable: Boolean,
                                   val requestedAt: Instant, val updatedAt: Instant,
                                   val completedAt: Instant?, val failure: TradingDeletionFailure?) {
    companion object {
        fun from(value: TradingDeletion) = TradingDeletionResponse(value.id.toString(), value.targetType,
            value.targetId.toString(), value.requestedScope, value.state,
            AffectedResourceSummary(value.accountCount, value.bookCount, value.importSessionCount, value.artifactCount,
                value.ledgerRevisionCount, value.analysisRunCount, value.reprocessingRunCount, value.countSnapshotStatus), value.revokedAt,
            value.primaryPurgeStatus.name, value.objectPurgeStatus.name, value.computeRuntimePurgeStatus.name,
            value.backupPurgeStatus.name,
            value.backupPurgeDueAt.takeUnless { value.backupPurgeStatus == PurgeComponentStatus.NOT_APPLICABLE },
            value.retryable, value.requestedAt, value.updatedAt,
            value.completedAt, value.failureCode?.let { TradingDeletionFailure(it, "Deletion could not be completed", value.retryable) })
    }
}
