package com.refinvest.core.tradingreview.port.deletion

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.DeletionRequest
import com.refinvest.core.tradingreview.domain.valueobject.DeletionPurgeComponent
import com.refinvest.core.tradingreview.domain.valueobject.DeletionRequestState
import com.refinvest.core.tradingreview.domain.valueobject.DeletionTargetType
import java.time.Instant

data class TradingDeletion(
    val id: Long, val owner: MemberId, val targetType: String, val targetId: Long,
    val requestedScope: String, val state: String, val deletionGeneration: Long,
    val accountCount: Long, val bookCount: Long,
    val revokedAt: Instant, val requestedAt: Instant, val updatedAt: Instant,
    val backupPurgeDueAt: Instant,
    val completedAt: Instant? = null,
    val failureCode: String? = null,
    val importSessionCount: Long = 0, val artifactCount: Long = 0,
    val primaryPurgeStatus: PurgeComponentStatus = PurgeComponentStatus.NOT_STARTED,
    val objectPurgeStatus: PurgeComponentStatus = PurgeComponentStatus.NOT_STARTED,
    val computeRuntimePurgeStatus: PurgeComponentStatus = PurgeComponentStatus.NOT_STARTED,
    val backupPurgeStatus: PurgeComponentStatus = PurgeComponentStatus.IN_PROGRESS,
    val retryable: Boolean = state !in setOf("COMPLETED", "FAILED"),
) {
    companion object {
        fun fromDomain(request: DeletionRequest): TradingDeletion {
            fun progress(vararg group: DeletionPurgeComponent): PurgeComponentStatus {
                val required = group.toSet().intersect(request.checklist.requiredComponents)
                return when {
                    required.isEmpty() -> PurgeComponentStatus.NOT_APPLICABLE
                    request.verifiedAbsent.keys.containsAll(required) -> PurgeComponentStatus.COMPLETED
                    request.state == DeletionRequestState.FAILED -> PurgeComponentStatus.FAILED
                    request.state == DeletionRequestState.REQUESTED -> PurgeComponentStatus.NOT_STARTED
                    DeletionPurgeComponent.PRIMARY_DATA in required && request.state == DeletionRequestState.CANCELLING_JOBS ->
                        PurgeComponentStatus.NOT_STARTED
                    else -> PurgeComponentStatus.IN_PROGRESS
                }
            }
            val resources = request.affectedResources
            return TradingDeletion(request.id.value, request.owner, request.scope.targetType.name,
                request.scope.target.value, request.scope.requestedScope, request.state.name, request.deletionGeneration,
                resources.accounts, resources.books, request.revokedAt, request.requestedAt, request.updatedAt,
                request.backupPurgeDueAt, request.completedAt, request.failureCode, resources.importSessions, resources.artifacts,
                progress(DeletionPurgeComponent.PRIMARY_DATA, DeletionPurgeComponent.DERIVED_RESULTS),
                progress(DeletionPurgeComponent.RAW_OBJECTS, DeletionPurgeComponent.RAW_KEYS, DeletionPurgeComponent.TEMPORARY_COPIES),
                progress(DeletionPurgeComponent.COMPUTE_RUNTIME),
                // Raw is excluded from backup. There is no persisted backup absence proof for subtree deletion.
                if (request.scope.targetType == DeletionTargetType.TRADING_SOURCE_ARTIFACT) PurgeComponentStatus.NOT_APPLICABLE
                else PurgeComponentStatus.IN_PROGRESS,
                !request.state.terminal)
        }
    }
}
