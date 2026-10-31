package com.refinvest.core.tradingreview.domain

import com.refinvest.core.common.domain.AggregateRoot
import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.valueobject.*
import java.time.Instant
import java.time.Duration

/** Deletion control aggregate. Required checklist and observed progress are different concepts. */
class DeletionRequest private constructor(
    id: DeletionRequestId,
    val owner: MemberId,
    val scope: DeletionScope,
    val state: DeletionRequestState,
    val deletionGeneration: Long,
    val affectedResources: DeletionAffectedResources,
    val revokedAt: Instant,
    val requestedAt: Instant,
    val updatedAt: Instant,
    val backupPurgeDueAt: Instant,
    val completedAt: Instant?,
    val failureCode: String?,
    val attemptCount: Int,
) : AggregateRoot<DeletionRequestId>(id) {
    val checklist: DeletionPurgeChecklist = DeletionPurgeChecklist.forScope(scope)
    init {
        require(id.value > 0 && owner.value > 0 && deletionGeneration > 0 && attemptCount >= 0)
        require(!updatedAt.isBefore(requestedAt) && !updatedAt.isBefore(revokedAt) && backupPurgeDueAt.isAfter(requestedAt))
        require(failureCode == null || Regex("[A-Z][A-Z0-9_]{0,79}").matches(failureCode))
        when (state) {
            DeletionRequestState.COMPLETED -> require(completedAt != null && failureCode == null)
            DeletionRequestState.FAILED -> require(completedAt == null && failureCode != null)
            else -> require(completedAt == null)
        }
        if (completedAt != null) require(!completedAt.isBefore(requestedAt) && !completedAt.isBefore(revokedAt) && !completedAt.isAfter(updatedAt))
    }
    companion object {
        fun request(id: DeletionRequestId, owner: MemberId, scope: DeletionScope, generation: Long,
                    resources: DeletionAffectedResources, now: Instant): DeletionRequest {
            resources.requireTarget(scope)
            return DeletionRequest(id, owner, scope, DeletionRequestState.REQUESTED, generation, resources,
                now, now, now, now.plus(Duration.ofDays(30)), null, null, 0)
        }
        /** Preserve persisted counters/labels/attempts, including incomplete historical counters. No purge is replayed. */
        fun restore(id: DeletionRequestId, owner: MemberId, scope: DeletionScope, state: DeletionRequestState,
                    generation: Long, resources: DeletionAffectedResources, revokedAt: Instant, requestedAt: Instant,
                    updatedAt: Instant, backupPurgeDueAt: Instant, completedAt: Instant?, failureCode: String?,
                    attemptCount: Int): DeletionRequest = DeletionRequest(id, owner, scope, state, generation, resources,
            revokedAt, requestedAt, updatedAt, backupPurgeDueAt, completedAt, failureCode, attemptCount)
    }
}
