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
    val version: Long,
    verifiedAbsent: Map<DeletionPurgeComponent, Instant>,
) : AggregateRoot<DeletionRequestId>(id) {
    val checklist: DeletionPurgeChecklist = DeletionPurgeChecklist.forScope(scope)
    val verifiedAbsent: Map<DeletionPurgeComponent, Instant> = java.util.Collections.unmodifiableMap(verifiedAbsent.toMap())
    init {
        require(id.value > 0 && owner.value > 0 && deletionGeneration > 0 && attemptCount >= 0 && version >= 0)
        require(checklist.requiredComponents.containsAll(verifiedAbsent.keys))
        require(verifiedAbsent.values.all { !it.isBefore(requestedAt) && !it.isAfter(updatedAt) })
        require(!updatedAt.isBefore(requestedAt) && !updatedAt.isBefore(revokedAt) && backupPurgeDueAt.isAfter(requestedAt))
        require(failureCode == null || Regex("[A-Z][A-Z0-9_]{0,79}").matches(failureCode))
        when (state) {
            DeletionRequestState.COMPLETED -> require(completedAt != null && failureCode == null)
            DeletionRequestState.FAILED -> require(completedAt == null && failureCode != null)
            else -> require(completedAt == null)
        }
        if (completedAt != null) require(!completedAt.isBefore(requestedAt) && !completedAt.isBefore(revokedAt) && !completedAt.isAfter(updatedAt))
    }

    fun start(now: Instant): DeletionRequest {
        require(state == DeletionRequestState.REQUESTED)
        return changed(now, DeletionRequestState.CANCELLING_JOBS)
    }

    /** The caller must enforce the committed tombstone and stop local admission before proceeding. */
    fun beginPrimaryPurge(now: Instant): DeletionRequest {
        require(state == DeletionRequestState.CANCELLING_JOBS)
        return changed(now, DeletionRequestState.PURGING_PRIMARY)
    }

    fun beginObjectPurge(now: Instant): DeletionRequest {
        require(state == DeletionRequestState.PURGING_PRIMARY)
        require(checklist.requiredComponents.filter { it == DeletionPurgeComponent.PRIMARY_DATA }
            .all(verifiedAbsent::containsKey))
        return changed(now, DeletionRequestState.PURGING_OBJECTS)
    }

    /** Only an actual component-specific absence check may call this; state/counts are not evidence. */
    fun verifyAbsent(component: DeletionPurgeComponent, now: Instant): DeletionRequest {
        require(!state.terminal && state != DeletionRequestState.REQUESTED)
        require(component in checklist.requiredComponents)
        require(component !in setOf(DeletionPurgeComponent.PRIMARY_DATA, DeletionPurgeComponent.DERIVED_RESULTS) ||
            state in setOf(DeletionRequestState.PURGING_PRIMARY, DeletionRequestState.PURGING_OBJECTS))
        require(component !in setOf(DeletionPurgeComponent.RAW_OBJECTS, DeletionPurgeComponent.RAW_KEYS) ||
            state == DeletionRequestState.PURGING_OBJECTS)
        return changed(now, verified = verifiedAbsent + (component to (verifiedAbsent[component] ?: now)))
    }

    fun complete(now: Instant): DeletionRequest {
        require(state == DeletionRequestState.PURGING_OBJECTS)
        require(verifiedAbsent.keys.containsAll(checklist.requiredComponents))
        return changed(now, DeletionRequestState.COMPLETED, completed = now, failure = null,
            backupDue = now.plus(Duration.ofDays(30)))
    }

    fun recordFailure(now: Instant, maxAttempts: Int = 5): DeletionRequest {
        require(!state.terminal && maxAttempts > 0)
        val attempts = Math.addExact(attemptCount, 1)
        return changed(now, if (attempts >= maxAttempts) DeletionRequestState.FAILED else state,
            attempts = attempts, failure = "DELETION_PARTIALLY_COMPLETED")
    }

    /** Fence durable workset/item progress even while the component is only partially purged. */
    fun recordExecutionProgress(now: Instant): DeletionRequest {
        require(!state.terminal && state != DeletionRequestState.REQUESTED)
        return changed(now)
    }

    private fun changed(now: Instant, next: DeletionRequestState = state,
                        verified: Map<DeletionPurgeComponent, Instant> = verifiedAbsent,
                        attempts: Int = attemptCount, failure: String? = failureCode,
                        completed: Instant? = completedAt, backupDue: Instant = backupPurgeDueAt): DeletionRequest {
        require(!now.isBefore(updatedAt))
        return DeletionRequest(id, owner, scope, next, deletionGeneration, affectedResources, revokedAt,
            requestedAt, now, backupDue, completed, failure, attempts, Math.addExact(version, 1), verified)
    }
    companion object {
        fun request(id: DeletionRequestId, owner: MemberId, scope: DeletionScope, generation: Long,
                    resources: DeletionAffectedResources, now: Instant): DeletionRequest {
            resources.requireTarget(scope)
            return DeletionRequest(id, owner, scope, DeletionRequestState.REQUESTED, generation, resources,
                now, now, now, now.plus(Duration.ofDays(30)), null, null, 0, 0, emptyMap())
        }
        /** Preserve persisted counters/labels/attempts, including incomplete historical counters. No purge is replayed. */
        fun restore(id: DeletionRequestId, owner: MemberId, scope: DeletionScope, state: DeletionRequestState,
                    generation: Long, resources: DeletionAffectedResources, revokedAt: Instant, requestedAt: Instant,
                    updatedAt: Instant, backupPurgeDueAt: Instant, completedAt: Instant?, failureCode: String?,
                    attemptCount: Int, version: Long = 0,
                    verifiedAbsent: Map<DeletionPurgeComponent, Instant> = emptyMap()): DeletionRequest =
            DeletionRequest(id, owner, scope, state, generation, resources, revokedAt, requestedAt, updatedAt,
                backupPurgeDueAt, completedAt, failureCode, attemptCount, version, verifiedAbsent)
    }
}
