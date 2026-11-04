package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.tradingreview.port.outbound.compute.deletion.TradingRuntimeDeletion
import com.refinvest.core.tradingreview.port.outbound.persistence.deletion.DeletionWorkItem
import com.refinvest.core.tradingreview.port.outbound.storage.workspace.TradingArtifactScope
import com.refinvest.core.tradingreview.domain.DeletionRequest
import com.refinvest.core.tradingreview.domain.valueobject.*
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** Durable manifests and item evidence; no lease acquisition, root transition or external I/O. */
internal class DeletionWorksetRows(private val jdbc: JdbcTemplate, private val scope: DeletionScopeRows) {
    fun planned(root: DeletionRequest, limit: Int): List<DeletionWorkItem> {
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

    fun recordItem(id: Long, item: DeletionWorkItem, at: Instant) {
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

    fun remaining(kind: String, id: Long): Boolean {
        require(kind in setOf("object", "copy", "runtime", "revision"))
        return exists("SELECT 1 FROM trading_deletion_${kind}_tasks WHERE request_id=? AND verified_at IS NULL", id)
    }

    fun prepareCopyTasks(root: DeletionRequest) {
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

    fun captureWorkset(root: DeletionRequest, now: java.time.Instant): DeletionRequest {
        // Preserve locators before any primary rows/keys can disappear.
        jdbc.update("""INSERT INTO trading_deletion_object_tasks
            (request_id,artifact_id,object_key,verified_at,session_id,artifact_role)
            SELECT ?,a.id,a.object_key,CASE WHEN a.retention_status='DELETED'
                AND a.object_key IS NULL AND a.wrapped_dek IS NULL AND a.key_version IS NULL
                THEN ?::timestamptz ELSE NULL END,a.import_session_id,a.artifact_role
            FROM trading_source_artifacts a JOIN trading_import_sessions s ON s.id=a.import_session_id
            JOIN trading_books b ON b.id=s.trading_book_id WHERE """ + scope.scopeClause(root) +
            " ON CONFLICT DO NOTHING", root.id.value, Timestamp.from(now), root.scope.target.value)
        val sessions = scope.sessionIds(root)
        jdbc.update("""INSERT INTO trading_deletion_session_tasks(request_id,session_id,book_id,account_id)
            SELECT ?,s.id,b.id,b.account_id FROM trading_import_sessions s JOIN trading_books b ON b.id=s.trading_book_id
            WHERE s.id IN ($sessions) ON CONFLICT DO NOTHING""", root.id.value, root.scope.target.value)
        jdbc.update("""INSERT INTO trading_deletion_runtime_tasks(request_id,run_id,attempt_token,compute_job_id,deletion_generation,current_deletion_generation,session_id)
            SELECT ?,r.id,r.attempt_token,r.compute_job_id,r.deletion_generation,
                GREATEST(r.deletion_generation,s.deletion_generation,b.deletion_generation,acct.deletion_generation,${root.deletionGeneration}),s.id
            FROM trading_normalization_runs r JOIN trading_import_sessions s ON s.id=r.import_session_id
            JOIN trading_books b ON b.id=s.trading_book_id JOIN trading_accounts acct ON acct.id=b.account_id
            WHERE r.import_session_id IN ($sessions) ON CONFLICT DO NOTHING""", root.id.value, root.scope.target.value)
        jdbc.update("""INSERT INTO trading_deletion_runtime_tasks(request_id,run_id,attempt_token,compute_job_id,deletion_generation,current_deletion_generation,session_id)
            SELECT ?,r.id,a.attempt_token,a.compute_job_id,r.deletion_generation,
                GREATEST(r.deletion_generation,s.deletion_generation,b.deletion_generation,acct.deletion_generation,${root.deletionGeneration}),s.id
            FROM trading_normalization_attempts a JOIN trading_normalization_runs r ON r.id=a.run_id
            JOIN trading_import_sessions s ON s.id=r.import_session_id JOIN trading_books b ON b.id=s.trading_book_id
            JOIN trading_accounts acct ON acct.id=b.account_id WHERE r.import_session_id IN ($sessions)
            ON CONFLICT (request_id,run_id,attempt_token) DO UPDATE SET compute_job_id=
                COALESCE(trading_deletion_runtime_tasks.compute_job_id,EXCLUDED.compute_job_id)""", root.id.value, root.scope.target.value)
        jdbc.update("""INSERT INTO trading_deletion_dispatch_tasks(request_id,dispatch_id,run_id,compute_key)
            SELECT ?,d.id,d.run_id,d.compute_key FROM trading_normalization_dispatches d
            JOIN trading_normalization_runs r ON r.id=d.run_id WHERE r.import_session_id IN ($sessions)
            ON CONFLICT DO NOTHING""", root.id.value, root.scope.target.value)
        if (root.scope.targetType.name != "TRADING_SOURCE_ARTIFACT") jdbc.update("""INSERT INTO trading_deletion_revision_tasks(request_id,revision_id)
            SELECT ?,id FROM trading_ledger_revisions WHERE import_session_id IN ($sessions)
            ON CONFLICT DO NOTHING""", root.id.value, root.scope.target.value)
        // Bounded local cancellation: no dispatch or read grant may restart this scope.
        // Remote cancellation/absence still needs its own task and is never inferred here.
        jdbc.update("""UPDATE trading_normalization_dispatches SET status='OBSOLETE',lease_owner=NULL,
            lease_expires_at=NULL,updated_at=? WHERE run_id IN
            (SELECT id FROM trading_normalization_runs WHERE import_session_id IN ($sessions))
            AND status IN ('PENDING','SUBMITTED','RESULT_READY')""", Timestamp.from(now), root.scope.target.value)
        jdbc.update("DELETE FROM trading_artifact_read_grants WHERE session_id IN ($sessions)", root.scope.target.value)
        scope.markArtifactsPending(root)
        jdbc.update("INSERT INTO trading_deletion_worksets(request_id,captured_at) VALUES (?,?)", root.id.value, Timestamp.from(now))
        return root
    }

    private fun exists(query: String, vararg args: Any): Boolean =
        jdbc.queryForObject("SELECT EXISTS($query)", Boolean::class.java, *args) == true

}
