package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.tradingreview.port.outbound.persistence.deletion.DeletionWorkItem
import com.refinvest.core.tradingreview.port.outbound.persistence.deletion.DeletionWorkLease
import com.refinvest.core.tradingreview.port.outbound.persistence.deletion.TradingDeletionWorkStore
import com.refinvest.core.tradingreview.domain.valueobject.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.Clock

@Repository
class JdbcTradingDeletionWorkStore(private val jdbc: JdbcTemplate, private val clock: Clock) : TradingDeletionWorkStore {
    private val execution = JdbcDeletionRequestExecution(jdbc)
    private val scope = DeletionScopeRows(jdbc, clock)
    private val worksets = DeletionWorksetRows(jdbc, scope)

    override fun claim(now: Instant, token: String, leaseDuration: Duration): DeletionWorkLease? {
        requireTransaction()
        require(!leaseDuration.isNegative && !leaseDuration.isZero)
        val id = jdbc.query("""SELECT id FROM trading_deletion_requests WHERE state NOT IN ('COMPLETED','FAILED')
            AND next_attempt_at<=? AND (execution_lease_expires_at IS NULL OR execution_lease_expires_at<=?)
            ORDER BY next_attempt_at,requested_at,id LIMIT 1 FOR UPDATE SKIP LOCKED""",
            { r, _ -> r.getLong(1) }, Timestamp.from(now), Timestamp.from(now)).firstOrNull() ?: return null
        val root = requireNotNull(DeletionRequestQueries(jdbc).find("d.id=?", id))
        val captured = exists("SELECT 1 FROM trading_deletion_worksets WHERE request_id=?", id)
        if (!captured) {
            if (root.state == DeletionRequestState.PURGING_OBJECTS)
                jdbc.update("UPDATE trading_deletion_requests SET legacy_coverage_unknown=TRUE WHERE id=?", id)
            worksets.captureWorkset(root, maxOf(now, clock.instant()))
        }
        worksets.prepareCopyTasks(root)
        val expires = maxOf(now, clock.instant()).plus(leaseDuration)
        jdbc.update("""UPDATE trading_deletion_requests SET execution_lease_token=?::uuid,
            execution_lease_expires_at=? WHERE id=?""", token, Timestamp.from(expires), id)
        return DeletionWorkLease(root, token, expires)
    }

    override fun plan(lease: DeletionWorkLease, now: Instant, limit: Int): List<DeletionWorkItem> {
        require(limit in 1..100)
        if (guard(lease, now) == null || lease.request.state == DeletionRequestState.REQUESTED) return emptyList()
        return worksets.planned(lease.request, limit)
    }

    override fun finish(lease: DeletionWorkLease, completed: List<DeletionWorkItem>, now: Instant,
                        retryAfter: Duration, failed: Boolean): Boolean {
        val checkedAt = guard(lease, now) ?: return false
        require(!retryAfter.isNegative && retryAfter <= Duration.ofSeconds(60))
        val root = lease.request
        val id = root.id.value
        val allowed = if (root.state == DeletionRequestState.REQUESTED) emptyList() else worksets.planned(root, 100)
        require(completed.distinct().size == completed.size && completed.all { it in allowed })
        for (item in completed) worksets.recordItem(id, item, checkedAt)
        var next = root
        if (!checkedAt.isBefore(root.requestedAt.plus(Duration.ofHours(24)))) next = root.expire(checkedAt)
        else if (failed) next = root.recordFailure(checkedAt)
        else {
            val dependenciesComplete = !scope.hasUnfinishedChildren(root)
            val trusted = !exists("SELECT 1 FROM trading_deletion_requests WHERE id=? AND legacy_coverage_unknown", id)
            val runtimeDone = !worksets.remaining("runtime", id)
            val copiesDone = !worksets.remaining("copy", id) && !worksets.remaining("revision", id) &&
                exists("SELECT 1 FROM trading_deletion_worksets WHERE request_id=? AND legacy_copies_verified_at IS NOT NULL", id)
            if (root.state != DeletionRequestState.REQUESTED && dependenciesComplete && trusted) {
                if (runtimeDone && DeletionPurgeComponent.COMPUTE_RUNTIME !in next.verifiedAbsent)
                    next = next.verifyAbsent(DeletionPurgeComponent.COMPUTE_RUNTIME, checkedAt)
                if (copiesDone && DeletionPurgeComponent.TEMPORARY_COPIES !in next.verifiedAbsent)
                    next = next.verifyAbsent(DeletionPurgeComponent.TEMPORARY_COPIES, checkedAt)
            }
            val prerequisites = next.verifiedAbsent.keys.containsAll(setOf(
                DeletionPurgeComponent.COMPUTE_RUNTIME, DeletionPurgeComponent.TEMPORARY_COPIES))
            when (root.state) {
                DeletionRequestState.REQUESTED -> next = root.start(checkedAt)
                DeletionRequestState.CANCELLING_JOBS -> if (prerequisites) next = next.beginPrimaryPurge(checkedAt)
                DeletionRequestState.PURGING_PRIMARY -> if (prerequisites) {
                    scope.purgeRows(root)
                    if (DeletionPurgeComponent.PRIMARY_DATA in next.checklist.requiredComponents) {
                        check(scope.primaryAbsent(root))
                        next = next.verifyAbsent(DeletionPurgeComponent.PRIMARY_DATA, checkedAt)
                    }
                    next = next.beginObjectPurge(checkedAt)
                }
                DeletionRequestState.PURGING_OBJECTS -> {
                    // Older workers could commit this state with live primary rows.
                    if (prerequisites && DeletionPurgeComponent.PRIMARY_DATA in next.checklist.requiredComponents &&
                        DeletionPurgeComponent.PRIMARY_DATA !in next.verifiedAbsent) {
                        scope.purgeRows(root)
                        check(scope.primaryAbsent(root))
                        next = next.verifyAbsent(DeletionPurgeComponent.PRIMARY_DATA, checkedAt)
                    }
                    if (!worksets.remaining("object", id)) {
                        if (DeletionPurgeComponent.RAW_OBJECTS !in next.verifiedAbsent)
                            next = next.verifyAbsent(DeletionPurgeComponent.RAW_OBJECTS, checkedAt)
                        if (!exists("""SELECT 1 FROM trading_source_artifacts a JOIN trading_deletion_object_tasks t
                                ON t.artifact_id=a.id WHERE t.request_id=? AND
                                (a.object_key IS NOT NULL OR a.wrapped_dek IS NOT NULL OR a.key_version IS NOT NULL)""", id) &&
                            DeletionPurgeComponent.RAW_KEYS !in next.verifiedAbsent)
                            next = next.verifyAbsent(DeletionPurgeComponent.RAW_KEYS, checkedAt)
                    }
                }
                else -> error("Terminal deletion cannot execute")
            }
            if (next.state in setOf(DeletionRequestState.PURGING_PRIMARY, DeletionRequestState.PURGING_OBJECTS) &&
                !worksets.remaining("revision", id) && dependenciesComplete && trusted &&
                DeletionPurgeComponent.DERIVED_RESULTS in next.checklist.requiredComponents &&
                DeletionPurgeComponent.DERIVED_RESULTS !in next.verifiedAbsent)
                next = next.verifyAbsent(DeletionPurgeComponent.DERIVED_RESULTS, checkedAt)
            if (next.state == DeletionRequestState.PURGING_OBJECTS && dependenciesComplete &&
                next.verifiedAbsent.keys.containsAll(next.checklist.requiredComponents)) next = next.complete(checkedAt)
        }
        if (next === root) next = root.recordExecutionProgress(checkedAt)
        check(execution.commit(root, next)) { "Deletion progress CAS failed" }
        val progressed = next.state != root.state || completed.isNotEmpty()
        val delay = when {
            failed -> Duration.ofSeconds(minOf(300L, 5L shl minOf(next.attemptCount-1, 6)))
            !progressed && retryAfter.isZero -> Duration.ofSeconds(5)
            else -> retryAfter
        }
        jdbc.update("""UPDATE trading_deletion_requests SET execution_lease_token=NULL,
            execution_lease_expires_at=NULL,next_attempt_at=? WHERE id=? AND execution_lease_token=?::uuid""",
            Timestamp.from(checkedAt.plus(delay)), id, lease.token)
        if (next.state == DeletionRequestState.FAILED)
            jdbc.update("""UPDATE trading_source_artifacts SET retention_status='DELETION_FAILED',
                deletion_failure='ARTIFACT_DELETION_FAILED',version=version+1 WHERE id IN
                (SELECT artifact_id FROM trading_deletion_object_tasks WHERE request_id=?)
                AND retention_status='DELETION_PENDING'""", id)
        return true
    }

    private fun guard(lease: DeletionWorkLease, now: Instant): Instant? {
        requireTransaction()
        val expected = lease.request
        val expires = jdbc.query("""SELECT execution_lease_expires_at FROM trading_deletion_requests
            WHERE id=? AND owner_member_id=? AND target_type=? AND target_id=? AND deletion_generation=?
            AND version=? AND state=? AND execution_lease_token=?::uuid FOR UPDATE""",
            { r, _ -> r.getTimestamp(1)?.toInstant() }, expected.id.value, expected.owner.value,
            expected.scope.targetType.name, expected.scope.target.value, expected.deletionGeneration,
            expected.version, expected.state.name, lease.token).firstOrNull() ?: return null
        val checkedAt = maxOf(now, clock.instant())
        return checkedAt.takeIf { it < expires && !expected.state.terminal }
    }

    private fun exists(query: String, vararg args: Any): Boolean =
        jdbc.queryForObject("SELECT EXISTS($query)", Boolean::class.java, *args) == true

    private fun requireTransaction() = check(TransactionSynchronizationManager.isActualTransactionActive()) {
        "Deletion work requires an active transaction"
    }

}
