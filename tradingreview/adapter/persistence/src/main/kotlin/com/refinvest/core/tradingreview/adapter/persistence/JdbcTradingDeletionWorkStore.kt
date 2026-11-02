package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.tradingreview.domain.DeletionRequest
import com.refinvest.core.tradingreview.domain.valueobject.*
import com.refinvest.core.tradingreview.port.deletion.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.Clock
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

@Repository
class JdbcTradingDeletionWorkStore(private val jdbc: JdbcTemplate, private val clock: Clock) : TradingDeletionWorkStore {
    private val execution = JdbcDeletionRequestExecution(jdbc)

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
            captureWorkset(root, root.toRequest(), maxOf(now, clock.instant()))
        }
        prepareCopyTasks(root)
        val expires = maxOf(now, clock.instant()).plus(leaseDuration)
        jdbc.update("""UPDATE trading_deletion_requests SET execution_lease_token=?::uuid,
            execution_lease_expires_at=? WHERE id=?""", token, Timestamp.from(expires), id)
        return DeletionWorkLease(root, token, expires)
    }

    override fun plan(lease: DeletionWorkLease, now: Instant, limit: Int): List<DeletionWorkItem> {
        require(limit in 1..100)
        if (guard(lease, now) == null || lease.request.state == DeletionRequestState.REQUESTED) return emptyList()
        return planned(lease.request, limit)
    }

    private fun planned(root: DeletionRequest, limit: Int): List<DeletionWorkItem> {
        val id = root.id.value
        val tasks = mutableListOf<DeletionWorkItem>()
        tasks += jdbc.query("""SELECT DISTINCT run_id::text,session_id,deletion_generation
            FROM trading_deletion_runtime_tasks WHERE request_id=? AND verified_at IS NULL
            AND session_id IS NOT NULL ORDER BY run_id::text LIMIT ?""", { r, _ ->
            DeletionWorkItem.Runtime(TradingRuntimeDeletion(NormalizationRunId(r.getString(1)),
                TradingImportSessionId(r.getLong(2)), r.getLong(3), root.id, root.deletionGeneration))
        }, id, limit)
        if (tasks.size < limit) tasks += jdbc.query("""SELECT scope_type,scope_id,artifact_role
            FROM trading_deletion_copy_tasks WHERE request_id=? AND verified_at IS NULL
            ORDER BY scope_type,scope_id,artifact_role LIMIT ?""", { r, _ ->
            DeletionWorkItem.Copies(copyScope(r.getString(1), r.getString(2), r.getString(3)))
        }, id, limit-tasks.size)
        if (tasks.size < limit) tasks += jdbc.query("""SELECT revision_id FROM trading_deletion_revision_tasks
            WHERE request_id=? AND verified_at IS NULL ORDER BY revision_id LIMIT ?""",
            { r, _ -> DeletionWorkItem.Revision(LedgerRevisionId(r.getObject(1, UUID::class.java))) }, id, limit-tasks.size)
        if (tasks.size < limit && !exists("""SELECT 1 FROM trading_deletion_worksets
                WHERE request_id=? AND legacy_copies_verified_at IS NOT NULL""", id))
            tasks += DeletionWorkItem.LegacyCopies
        if (root.state == DeletionRequestState.PURGING_OBJECTS && tasks.size < limit)
            tasks += jdbc.query("""SELECT artifact_id,object_key FROM trading_deletion_object_tasks
                WHERE request_id=? AND verified_at IS NULL AND object_key IS NOT NULL ORDER BY artifact_id LIMIT ?""",
                { r, _ -> DeletionWorkItem.Object(TradingSourceArtifactId(r.getLong(1)), r.getString(2)) }, id, limit-tasks.size)
        return tasks
    }

    override fun finish(lease: DeletionWorkLease, completed: List<DeletionWorkItem>, now: Instant,
                        retryAfter: Duration, failed: Boolean): Boolean {
        val checkedAt = guard(lease, now) ?: return false
        require(!retryAfter.isNegative && retryAfter <= Duration.ofSeconds(60))
        val root = lease.request
        val id = root.id.value
        val allowed = if (root.state == DeletionRequestState.REQUESTED) emptyList() else planned(root, 100)
        require(completed.distinct().size == completed.size && completed.all { it in allowed })
        for (item in completed) recordItem(id, item, checkedAt)
        var next = root
        if (!checkedAt.isBefore(root.requestedAt.plus(Duration.ofHours(24)))) next = root.expire(checkedAt)
        else if (failed) next = root.recordFailure(checkedAt)
        else {
            val dependenciesComplete = !hasUnfinishedChildren(root.toRequest())
            val trusted = !exists("SELECT 1 FROM trading_deletion_requests WHERE id=? AND legacy_coverage_unknown", id)
            val runtimeDone = !remaining("runtime", id)
            val copiesDone = !remaining("copy", id) && !remaining("revision", id) &&
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
                    purgeRows(root.toRequest())
                    if (DeletionPurgeComponent.PRIMARY_DATA in next.checklist.requiredComponents) {
                        check(primaryAbsent(root.toRequest()))
                        next = next.verifyAbsent(DeletionPurgeComponent.PRIMARY_DATA, checkedAt)
                    }
                    next = next.beginObjectPurge(checkedAt)
                }
                DeletionRequestState.PURGING_OBJECTS -> {
                    // Older workers could commit this state with live primary rows.
                    if (prerequisites && DeletionPurgeComponent.PRIMARY_DATA in next.checklist.requiredComponents &&
                        DeletionPurgeComponent.PRIMARY_DATA !in next.verifiedAbsent) {
                        purgeRows(root.toRequest())
                        check(primaryAbsent(root.toRequest()))
                        next = next.verifyAbsent(DeletionPurgeComponent.PRIMARY_DATA, checkedAt)
                    }
                    if (!remaining("object", id)) {
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
                !remaining("revision", id) && dependenciesComplete && trusted &&
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

    private fun recordItem(id: Long, item: DeletionWorkItem, at: Instant) {
        val timestamp = Timestamp.from(at)
        when (item) {
            is DeletionWorkItem.Runtime -> jdbc.update("""UPDATE trading_deletion_runtime_tasks SET verified_at=?
                WHERE request_id=? AND run_id=?::uuid AND session_id=? AND deletion_generation=? AND verified_at IS NULL""",
                timestamp, id, item.command.runId.value, item.command.sessionId.value, item.command.sourceGeneration)
            is DeletionWorkItem.Copies -> {
                val (type, identity, role) = copyIdentity(item.scope)
                jdbc.update("""UPDATE trading_deletion_copy_tasks SET verified_at=? WHERE request_id=?
                    AND scope_type=? AND scope_id=? AND artifact_role=? AND verified_at IS NULL""", timestamp, id, type, identity, role)
            }
            is DeletionWorkItem.Revision -> jdbc.update("""UPDATE trading_deletion_revision_tasks SET verified_at=?
                WHERE request_id=? AND revision_id=? AND verified_at IS NULL""", timestamp, id, item.id.value)
            is DeletionWorkItem.Object -> {
                jdbc.update("""UPDATE trading_source_artifacts SET retention_status='DELETED',object_key=NULL,
                    wrapped_dek=NULL,key_version=NULL,filename=NULL,deleted_at=?,deletion_failure=NULL,version=version+1
                    WHERE id=?""", timestamp, item.artifactId.value)
                jdbc.update("""UPDATE trading_deletion_object_tasks SET verified_at=?
                    WHERE request_id=? AND artifact_id=? AND verified_at IS NULL""", timestamp, id, item.artifactId.value)
            }
            DeletionWorkItem.LegacyCopies -> jdbc.update("""UPDATE trading_deletion_worksets SET legacy_copies_verified_at=?
                WHERE request_id=? AND legacy_copies_verified_at IS NULL""", timestamp, id)
        }
    }

    private fun remaining(kind: String, id: Long): Boolean {
        require(kind in setOf("object", "copy", "runtime", "revision"))
        return exists("SELECT 1 FROM trading_deletion_${kind}_tasks WHERE request_id=? AND verified_at IS NULL", id)
    }

    private fun exists(query: String, vararg args: Any): Boolean =
        jdbc.queryForObject("SELECT EXISTS($query)", Boolean::class.java, *args) == true

    private fun requireTransaction() = check(TransactionSynchronizationManager.isActualTransactionActive()) {
        "Deletion work requires an active transaction"
    }

    private fun prepareCopyTasks(root: DeletionRequest) {
        val id = root.id.value
        if (root.scope.targetType != DeletionTargetType.TRADING_SOURCE_ARTIFACT)
            jdbc.update("""INSERT INTO trading_deletion_copy_tasks(request_id,scope_type,scope_id)
                SELECT request_id,'SESSION',session_id::text FROM trading_deletion_session_tasks WHERE request_id=?
                ON CONFLICT DO NOTHING""", id)
        jdbc.update("""INSERT INTO trading_deletion_copy_tasks(request_id,scope_type,scope_id,artifact_role)
            SELECT request_id,'RAW_SLOT',session_id::text,artifact_role FROM trading_deletion_object_tasks
            WHERE request_id=? AND session_id IS NOT NULL AND artifact_role IS NOT NULL ON CONFLICT DO NOTHING""", id)
        jdbc.update("""INSERT INTO trading_deletion_copy_tasks(request_id,scope_type,scope_id)
            SELECT request_id,'ARTIFACT',artifact_id::text FROM trading_deletion_object_tasks WHERE request_id=?
            ON CONFLICT DO NOTHING""", id)
        jdbc.update("""INSERT INTO trading_deletion_copy_tasks(request_id,scope_type,scope_id)
            SELECT request_id,'RUN',run_id::text FROM trading_deletion_runtime_tasks WHERE request_id=?
            ON CONFLICT DO NOTHING""", id)
    }

    private fun copyScope(type: String, id: String, role: String): TradingArtifactScope = when (type) {
        "SESSION" -> TradingArtifactScope.SessionUploads(TradingImportSessionId(id.toLong()))
        "RAW_SLOT" -> TradingArtifactScope.RawSlot(TradingImportSessionId(id.toLong()), TradingArtifactRole.valueOf(role))
        "ARTIFACT" -> TradingArtifactScope.LegacyArtifact(TradingSourceArtifactId(id.toLong()))
        "RUN" -> TradingArtifactScope.Run(NormalizationRunId(id))
        else -> error("Unknown deletion copy scope")
    }

    private fun copyIdentity(scope: TradingArtifactScope): Triple<String, String, String> = when (scope) {
        is TradingArtifactScope.SessionUploads -> Triple("SESSION", scope.sessionId.value.toString(), "")
        is TradingArtifactScope.RawSlot -> Triple("RAW_SLOT", scope.sessionId.value.toString(), scope.role.name)
        is TradingArtifactScope.LegacyArtifact -> Triple("ARTIFACT", scope.artifactId.value.toString(), "")
        is TradingArtifactScope.Run -> Triple("RUN", scope.runId.value, "")
        is TradingArtifactScope.Revision -> error("Revision files use their own capability")
    }

    override fun remediate(failedId: DeletionRequestId, newId: DeletionRequestId, now: Instant): DeletionRequest {
        requireTransaction()
        check(exists("SELECT 1 FROM trading_deletion_requests WHERE id=? FOR UPDATE", failedId.value))
        val failed = requireNotNull(DeletionRequestQueries(jdbc).find("d.id=?", failedId.value))
        require(failed.state == DeletionRequestState.FAILED) { "Only failed deletion can be remediated" }
        DeletionRequestQueries(jdbc).find("d.remediation_of=?", failedId.value)?.let { return it }
        val root = DeletionRequest.remediation(newId, failed, now)
        val r = DeletionRequestPersistenceMapper.toRecord(root)
        jdbc.update("""INSERT INTO trading_deletion_requests(id,owner_member_id,target_type,target_id,
            requested_scope,state,deletion_generation,account_count,book_count,import_session_count,artifact_count,
            revoked_at,requested_at,updated_at,backup_purge_due_at,remediation_of,
            ledger_revision_count,analysis_run_count,reprocessing_run_count,count_snapshot_status,legacy_coverage_unknown)
            SELECT ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,ledger_revision_count,analysis_run_count,
            reprocessing_run_count,count_snapshot_status,legacy_coverage_unknown
            FROM trading_deletion_requests WHERE id=?""", r.id, r.ownerId, r.targetType, r.targetId,
            r.requestedScope, r.state, r.deletionGeneration, r.accountCount, r.bookCount, r.importSessionCount,
            r.artifactCount, Timestamp.from(r.revokedAt), Timestamp.from(r.requestedAt), Timestamp.from(r.updatedAt),
            Timestamp.from(r.backupPurgeDueAt), failedId.value, failedId.value)
        val manifests = mapOf(
            "worksets" to "captured_at,legacy_copies_verified_at",
            "object_tasks" to "artifact_id,object_key,verified_at,session_id,artifact_role",
            "session_tasks" to "session_id,book_id,account_id",
            "runtime_tasks" to "run_id,attempt_token,compute_job_id,deletion_generation,current_deletion_generation,session_id,verified_at",
            "dispatch_tasks" to "dispatch_id,run_id,compute_key",
            "revision_tasks" to "revision_id,verified_at",
            "copy_tasks" to "scope_type,scope_id,artifact_role,verified_at")
        for ((table, columns) in manifests) jdbc.update("""INSERT INTO trading_deletion_$table(request_id,$columns)
            SELECT ?,$columns FROM trading_deletion_$table WHERE request_id=?""", newId.value, failedId.value)
        // Inherit item evidence and fences, while retaining the original failed aggregate unchanged.
        return root
    }

    private fun captureWorkset(root: DeletionRequest, request: Request, now: java.time.Instant): DeletionRequest {
        // Preserve locators before any primary rows/keys can disappear.
        jdbc.update("""INSERT INTO trading_deletion_object_tasks
            (request_id,artifact_id,object_key,verified_at,session_id,artifact_role)
            SELECT ?,a.id,a.object_key,CASE WHEN a.retention_status='DELETED'
                AND a.object_key IS NULL AND a.wrapped_dek IS NULL AND a.key_version IS NULL
                THEN ?::timestamptz ELSE NULL END,a.import_session_id,a.artifact_role
            FROM trading_source_artifacts a JOIN trading_import_sessions s ON s.id=a.import_session_id
            JOIN trading_books b ON b.id=s.trading_book_id WHERE """ + scopeClause(request) +
            " ON CONFLICT DO NOTHING", request.id, Timestamp.from(now), request.target)
        val sessions = sessionIds(request)
        jdbc.update("""INSERT INTO trading_deletion_session_tasks(request_id,session_id,book_id,account_id)
            SELECT ?,s.id,b.id,b.account_id FROM trading_import_sessions s JOIN trading_books b ON b.id=s.trading_book_id
            WHERE s.id IN ($sessions) ON CONFLICT DO NOTHING""", request.id, request.target)
        jdbc.update("""INSERT INTO trading_deletion_runtime_tasks(request_id,run_id,attempt_token,compute_job_id,deletion_generation,current_deletion_generation,session_id)
            SELECT ?,r.id,r.attempt_token,r.compute_job_id,r.deletion_generation,
                GREATEST(r.deletion_generation,s.deletion_generation,b.deletion_generation,acct.deletion_generation,${root.deletionGeneration}),s.id
            FROM trading_normalization_runs r JOIN trading_import_sessions s ON s.id=r.import_session_id
            JOIN trading_books b ON b.id=s.trading_book_id JOIN trading_accounts acct ON acct.id=b.account_id
            WHERE r.import_session_id IN ($sessions) ON CONFLICT DO NOTHING""", request.id, request.target)
        jdbc.update("""INSERT INTO trading_deletion_runtime_tasks(request_id,run_id,attempt_token,compute_job_id,deletion_generation,current_deletion_generation,session_id)
            SELECT ?,r.id,a.attempt_token,a.compute_job_id,r.deletion_generation,
                GREATEST(r.deletion_generation,s.deletion_generation,b.deletion_generation,acct.deletion_generation,${root.deletionGeneration}),s.id
            FROM trading_normalization_attempts a JOIN trading_normalization_runs r ON r.id=a.run_id
            JOIN trading_import_sessions s ON s.id=r.import_session_id JOIN trading_books b ON b.id=s.trading_book_id
            JOIN trading_accounts acct ON acct.id=b.account_id WHERE r.import_session_id IN ($sessions)
            ON CONFLICT (request_id,run_id,attempt_token) DO UPDATE SET compute_job_id=
                COALESCE(trading_deletion_runtime_tasks.compute_job_id,EXCLUDED.compute_job_id)""", request.id, request.target)
        jdbc.update("""INSERT INTO trading_deletion_dispatch_tasks(request_id,dispatch_id,run_id,compute_key)
            SELECT ?,d.id,d.run_id,d.compute_key FROM trading_normalization_dispatches d
            JOIN trading_normalization_runs r ON r.id=d.run_id WHERE r.import_session_id IN ($sessions)
            ON CONFLICT DO NOTHING""", request.id, request.target)
        if (request.type != "TRADING_SOURCE_ARTIFACT") jdbc.update("""INSERT INTO trading_deletion_revision_tasks(request_id,revision_id)
            SELECT ?,id FROM trading_ledger_revisions WHERE import_session_id IN ($sessions)
            ON CONFLICT DO NOTHING""", request.id, request.target)
        // Bounded local cancellation: no dispatch or read grant may restart this scope.
        // Remote cancellation/absence still needs its own task and is never inferred here.
        jdbc.update("""UPDATE trading_normalization_dispatches SET status='OBSOLETE',lease_owner=NULL,
            lease_expires_at=NULL,updated_at=? WHERE run_id IN
            (SELECT id FROM trading_normalization_runs WHERE import_session_id IN ($sessions))
            AND status IN ('PENDING','SUBMITTED','RESULT_READY')""", Timestamp.from(now), request.target)
        jdbc.update("DELETE FROM trading_artifact_read_grants WHERE session_id IN ($sessions)", request.target)
        markArtifactsPending(request)
        jdbc.update("INSERT INTO trading_deletion_worksets(request_id,captured_at) VALUES (?,?)", request.id, Timestamp.from(now))
        return root
    }

    private fun hasUnfinishedChildren(request: Request): Boolean {
        if (request.type == "TRADING_SOURCE_ARTIFACT") return false
        val relation = when (request.type) {
            "TRADING_ACCOUNT" -> "t.account_id=?"
            "TRADING_BOOK" -> "t.book_id=?"
            "TRADING_IMPORT_SESSION" -> "t.session_id=?"
            else -> error("Unknown deletion scope")
        }
        val descendants = when (request.type) {
            "TRADING_ACCOUNT" -> "'TRADING_BOOK','TRADING_IMPORT_SESSION','TRADING_SOURCE_ARTIFACT'"
            "TRADING_BOOK" -> "'TRADING_IMPORT_SESSION','TRADING_SOURCE_ARTIFACT'"
            else -> "'TRADING_SOURCE_ARTIFACT'"
        }
        return jdbc.queryForObject("""WITH RECURSIVE repaired AS (
            SELECT d.id,d.remediation_of,d.owner_member_id,d.target_type,d.target_id,d.deletion_generation
            FROM trading_deletion_requests d WHERE d.state='COMPLETED' AND
                (SELECT count(*) FROM trading_deletion_checkpoints c WHERE c.request_id=d.id) =
                    CASE WHEN d.target_type='TRADING_SOURCE_ARTIFACT' THEN 4 ELSE 6 END
            UNION ALL
            SELECT d.id,d.remediation_of,d.owner_member_id,d.target_type,d.target_id,d.deletion_generation
            FROM trading_deletion_requests d JOIN repaired r ON r.remediation_of=d.id
                AND r.owner_member_id=d.owner_member_id AND r.target_type=d.target_type
                AND r.target_id=d.target_id AND r.deletion_generation=d.deletion_generation)
            SELECT EXISTS(SELECT 1 FROM trading_deletion_requests d
            JOIN trading_deletion_session_tasks t ON t.request_id=d.id
            WHERE d.id<>? AND d.owner_member_id=? AND d.target_type IN ($descendants)
                AND NOT EXISTS (SELECT 1 FROM repaired r WHERE r.id=d.id) AND $relation)""",
            Boolean::class.java, request.id, request.owner, request.target) == true
    }

    private fun sessionIds(request: Request): String = "SELECT s.id FROM trading_import_sessions s " +
        "JOIN trading_books b ON b.id=s.trading_book_id WHERE s.owner_member_id=${request.owner} AND " + when (request.type) {
            "TRADING_SOURCE_ARTIFACT" -> "s.id IN (SELECT import_session_id FROM trading_source_artifacts WHERE id=?)"
            "TRADING_IMPORT_SESSION" -> "s.id=?"
            "TRADING_BOOK" -> "b.id=?"
            "TRADING_ACCOUNT" -> "b.account_id=?"
            else -> error("Unknown deletion scope")
        }

    private fun primaryAbsent(request: Request): Boolean {
        val table = when (request.type) {
            "TRADING_IMPORT_SESSION" -> "trading_import_sessions"
            "TRADING_BOOK" -> "trading_books"
            "TRADING_ACCOUNT" -> "trading_accounts"
            else -> error("Raw-only request does not purge primary data")
        }
        return jdbc.queryForObject("SELECT NOT EXISTS(SELECT 1 FROM $table WHERE id=? AND owner_member_id=?)",
            Boolean::class.java, request.target, request.owner) == true
    }

    private fun scopeClause(request: Request): String = "s.owner_member_id=${request.owner} AND " + when (request.type) {
        "TRADING_SOURCE_ARTIFACT" -> "a.id=?"
        "TRADING_IMPORT_SESSION" -> "s.id=?"
        "TRADING_BOOK" -> "b.id=?"
        "TRADING_ACCOUNT" -> "b.account_id=?"
        else -> error("Unknown deletion scope")
    }

    private fun markArtifactsPending(request: Request) {
        jdbc.update("""UPDATE trading_source_artifacts a SET retention_status='DELETION_PENDING', version=a.version+1
            FROM trading_import_sessions s JOIN trading_books b ON b.id=s.trading_book_id
            WHERE a.import_session_id=s.id AND """ + scopeClause(request) +
            " AND a.retention_status IN ('ACTIVE','RETENTION_SCHEDULED')", request.target)
    }

    private fun purgeRows(request: Request) {
        if (request.type == "TRADING_SOURCE_ARTIFACT") {
            jdbc.update("""INSERT INTO trading_deleted_resources (owner_member_id, target_type, target_id, deletion_request_id)
                VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING""", request.owner, request.type, request.target, request.id)
            return
        }
        jdbc.update("""INSERT INTO trading_deleted_resources (owner_member_id, target_type, target_id, deletion_request_id)
            SELECT s.owner_member_id, 'TRADING_IMPORT_SESSION', s.id, ? FROM trading_import_sessions s
            JOIN trading_books b ON b.id=s.trading_book_id WHERE """ + scopeClause(request) +
            " ON CONFLICT DO NOTHING", request.id, request.target)
        purgeNormalizationAndLedger(request)
        when (request.type) {
            "TRADING_IMPORT_SESSION" -> {
                jdbc.update("DELETE FROM trading_source_artifacts WHERE import_session_id=?", request.target)
                jdbc.update("DELETE FROM trading_import_sessions WHERE id=? AND owner_member_id=?", request.target, request.owner)
            }
            "TRADING_BOOK" -> {
                jdbc.update("""DELETE FROM trading_source_artifacts WHERE import_session_id IN
                    (SELECT id FROM trading_import_sessions WHERE trading_book_id=?)""", request.target)
                jdbc.update("DELETE FROM trading_import_sessions WHERE trading_book_id=?", request.target)
                jdbc.update("DELETE FROM trading_books WHERE id=? AND owner_member_id=?", request.target, request.owner)
            }
            "TRADING_ACCOUNT" -> {
                jdbc.update("""INSERT INTO trading_deleted_resources (owner_member_id, target_type, target_id, deletion_request_id)
                    SELECT owner_member_id, 'TRADING_BOOK', id, ? FROM trading_books WHERE account_id=?
                    ON CONFLICT DO NOTHING""", request.id, request.target)
                jdbc.update("""DELETE FROM trading_source_artifacts WHERE import_session_id IN
                    (SELECT s.id FROM trading_import_sessions s JOIN trading_books b ON b.id=s.trading_book_id
                    WHERE b.account_id=?)""", request.target)
                jdbc.update("""DELETE FROM trading_import_sessions WHERE trading_book_id IN
                    (SELECT id FROM trading_books WHERE account_id=?)""", request.target)
                jdbc.update("DELETE FROM trading_books WHERE account_id=?", request.target)
                jdbc.update("DELETE FROM trading_accounts WHERE id=? AND owner_member_id=?", request.target, request.owner)
            }
        }
        jdbc.update("""INSERT INTO trading_deleted_resources (owner_member_id, target_type, target_id, deletion_request_id)
            VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING""", request.owner, request.type, request.target, request.id)
    }

    private fun purgeNormalizationAndLedger(request: Request) {
        enqueueKnownStalePayloads(request)
        // Book/session pointers must be cleared before the immutable revision rows are removed.
        val sessionIds = "SELECT s.id FROM trading_import_sessions s JOIN trading_books b " +
            "ON b.id=s.trading_book_id WHERE " + scopeClause(request)
        val target = request.target
        val revisionIds = "SELECT id FROM trading_ledger_revisions WHERE import_session_id IN ($sessionIds)"
        jdbc.update("UPDATE trading_books SET latest_ledger_revision_id=NULL WHERE latest_ledger_revision_id IN ($revisionIds)", target)
        jdbc.update("UPDATE trading_import_sessions SET created_ledger_revision_id=NULL WHERE id IN ($sessionIds)", target)
        jdbc.update("DELETE FROM trading_ledger_analytics_supplement WHERE revision_id IN ($revisionIds)", target)
        jdbc.update("DELETE FROM trading_ledger_allocations WHERE revision_id IN ($revisionIds)", target)
        jdbc.update("DELETE FROM trading_ledger_revision_records WHERE revision_id IN ($revisionIds)", target)
        jdbc.update("DELETE FROM trading_ledger_review_units WHERE revision_id IN ($revisionIds)", target)
        jdbc.update("DELETE FROM trading_ledger_revisions WHERE import_session_id IN ($sessionIds)", target)
        jdbc.update("""DELETE FROM trading_records r WHERE r.source_evidence_snapshot_id IN (
            SELECT e.snapshot_id FROM trading_source_evidence_snapshots e WHERE e.import_session_id IN ($sessionIds))
            AND NOT EXISTS (SELECT 1 FROM trading_ledger_revision_records m
            WHERE m.trading_book_id=r.trading_book_id AND m.record_id=r.record_id)""", target)
        jdbc.update("DELETE FROM trading_source_evidence_snapshots WHERE import_session_id IN ($sessionIds)", target)
        jdbc.update("""UPDATE trading_books b SET latest_ledger_revision_id=(SELECT r.id
            FROM trading_ledger_revisions r WHERE r.trading_book_id=b.id
            ORDER BY r.revision_number DESC LIMIT 1)
            WHERE b.latest_ledger_revision_id IS NULL AND b.id IN (
                SELECT s.trading_book_id FROM trading_import_sessions s WHERE s.id IN ($sessionIds))""", target)
        val runIds = "SELECT id FROM trading_normalization_runs WHERE import_session_id IN ($sessionIds)"
        // Pending ACK contains only opaque transport identity and must survive primary purge.
        jdbc.update("DELETE FROM trading_normalization_ack_dispatches WHERE run_id IN ($runIds) AND status<>'PENDING'", target)
        jdbc.update("DELETE FROM trading_normalization_result_staging WHERE run_id IN ($runIds)", target)
        jdbc.update("DELETE FROM trading_normalization_audit WHERE run_id IN ($runIds)", target)
        jdbc.update("DELETE FROM trading_normalization_dispatches WHERE run_id IN ($runIds)", target)
        jdbc.update("DELETE FROM trading_normalization_attempts WHERE run_id IN ($runIds)", target)
        jdbc.update("DELETE FROM trading_normalization_runs WHERE import_session_id IN ($sessionIds)", target)
        jdbc.update("DELETE FROM trading_artifact_read_grants WHERE session_id IN ($sessionIds)", target)
    }

    private fun enqueueKnownStalePayloads(request: Request) {
        val now = clock.instant()
        val known = jdbc.query("""SELECT r.id::text,s.id,r.compute_job_id,stage.payload_id,
            stage.transport_sha256,stage.result_hash,stage.attempt_token::text,
            r.deletion_generation,r.retry_policy_json::text,stage.payload_expires_at,
            GREATEST(s.deletion_generation,
                CASE WHEN b.status='DELETION_PENDING' THEN b.deletion_generation ELSE 0 END,
                CASE WHEN a.status='DELETION_PENDING' THEN a.deletion_generation ELSE 0 END)
            FROM trading_normalization_result_staging stage
            JOIN trading_normalization_runs r ON r.id=stage.run_id
            JOIN trading_import_sessions s ON s.id=r.import_session_id
            JOIN trading_books b ON b.id=s.trading_book_id
            JOIN trading_accounts a ON a.id=b.account_id
            WHERE """ + scopeClause(request) + """ AND r.status='RESULT_READY'
            AND r.compute_job_id=stage.compute_job_id AND r.attempt_token=stage.attempt_token
            AND r.deletion_generation=stage.deletion_generation
            AND stage.payload_expires_at>?""", { rs, _ ->
            KnownPayload(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getLong(8),
                rs.getString(9), rs.getTimestamp(10).toInstant(), rs.getLong(11))
        }, request.target, Timestamp.from(now))
        for (payload in known) {
            if (payload.currentGeneration <= payload.deletionGeneration) continue
            val key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                "${payload.runId}:${payload.jobId}:${payload.payloadId}:DISCARDED_STALE"
                    .toByteArray(Charsets.UTF_8)))
            jdbc.update("""INSERT INTO trading_normalization_ack_dispatches
                (id,run_id,core_import_session_id,compute_job_id,payload_id,payload_sha256,
                result_hash,attempt_token,deletion_generation,current_deletion_generation,
                disposition,idempotency_key,retry_policy_json,payload_expires_at,status,
                next_attempt_at,created_at,updated_at)
                VALUES (?::uuid,?::uuid,?,?,?,?,?,?::uuid,?,?,'DISCARDED_STALE',?,?::jsonb,?,
                'PENDING',?,?,?) ON CONFLICT (idempotency_key) DO NOTHING""",
                UUID.randomUUID().toString(), payload.runId, payload.sessionId, payload.jobId,
                payload.payloadId, payload.payloadSha256, payload.resultHash, payload.attemptToken,
                payload.deletionGeneration, payload.currentGeneration, key,
                payload.retryPolicyJson, Timestamp.from(payload.expiresAt), Timestamp.from(now),
                Timestamp.from(now), Timestamp.from(now))
        }
    }

    private data class KnownPayload(val runId: String, val sessionId: Long, val jobId: String,
        val payloadId: String, val payloadSha256: String, val resultHash: String,
        val attemptToken: String, val deletionGeneration: Long, val retryPolicyJson: String,
        val expiresAt: java.time.Instant, val currentGeneration: Long)


    private fun DeletionRequest.toRequest() = Request(id.value, owner.value, scope.targetType.name,
        scope.target.value, state.name)
    private data class Request(val id: Long, val owner: Long, val type: String, val target: Long, val state: String)
}
