package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.tradingreview.domain.valueobject.NormalizationResultVersionPin
import com.refinvest.core.tradingreview.domain.valueobject.NormalizationDispatchStatus
import com.refinvest.core.tradingreview.domain.importsession.NormalizationDispatch
import com.refinvest.core.tradingreview.domain.importsession.NormalizationRun
import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportSessionId
import com.refinvest.core.tradingreview.port.*
import java.nio.charset.StandardCharsets
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper

@Repository
class JdbcTradingNormalizationResultStore(private val jdbc: JdbcTemplate, private val mapper: ObjectMapper) :
    TradingNormalizationResultStore, TradingLedgerPublicationReader {
    override fun publishedForSession(sessionId: TradingImportSessionId,
                                     owner: MemberId): PublishedLedgerSummary? = jdbc.query("""
        SELECT r.id::text,r.quality_report_json::text,r.capability_json::text,r.published_at
        FROM trading_ledger_revisions r JOIN trading_import_sessions s ON s.id=r.import_session_id
        JOIN trading_books b ON b.id=s.trading_book_id
        JOIN trading_accounts a ON a.id=b.account_id
        WHERE r.import_session_id=? AND s.owner_member_id=? AND s.status='ACCEPTED'
        AND NOT s.deletion_pending AND b.status='ACTIVE' AND a.status='ACTIVE'
        ORDER BY r.published_at DESC LIMIT 1""", { rs, _ ->
        PublishedLedgerSummary(rs.getString(1), rs.getString(2), rs.getString(3),
            rs.getTimestamp(4).toInstant())
    }, sessionId.value, owner.value).firstOrNull()
    override fun claimReady(now: Instant, lease: Duration, workerId: String): NormalizationDispatch? =
        jdbc.query("""WITH due AS (SELECT id FROM trading_normalization_dispatches
            WHERE status='RESULT_READY' AND next_attempt_at<=? AND
            (lease_expires_at IS NULL OR lease_expires_at<=?)
            ORDER BY next_attempt_at,id LIMIT 1 FOR UPDATE SKIP LOCKED)
            UPDATE trading_normalization_dispatches d SET lease_owner=?::uuid,
            lease_expires_at=?,result_attempt_count=result_attempt_count+1,updated_at=?
            FROM due WHERE d.id=due.id RETURNING d.*""", { rs, _ ->
            dispatch(rs).copy(attemptCount = rs.getInt("result_attempt_count")) },
            Timestamp.from(now), Timestamp.from(now), workerId,
            Timestamp.from(now.plus(lease)), Timestamp.from(now)).firstOrNull()

    override fun hasReadyLease(dispatchId: String, workerId: String, now: Instant): Boolean =
        jdbc.query("""SELECT status='RESULT_READY' AND lease_owner=?::uuid AND lease_expires_at>?
            FROM trading_normalization_dispatches WHERE id=?::uuid FOR UPDATE""",
            { rs, _ -> rs.getBoolean(1) }, workerId, Timestamp.from(now), dispatchId)
            .firstOrNull() == true

    override fun retryReady(dispatchId: String, workerId: String, code: String,
                            next: Instant, now: Instant): Boolean = jdbc.update("""
        UPDATE trading_normalization_dispatches SET next_attempt_at=?,last_failure_code=?,
        lease_owner=NULL,lease_expires_at=NULL,updated_at=? WHERE id=?::uuid AND
        lease_owner=?::uuid AND lease_expires_at>? AND status='RESULT_READY'""",
        Timestamp.from(next), code, Timestamp.from(now), dispatchId, workerId, Timestamp.from(now)) == 1

    override fun beginStage(run: NormalizationRun, descriptor: NormalizationResultDescriptor, now: Instant) {
        if (run.resultVersionPin.schemaVersion != descriptor.resultSchemaVersion ||
            run.resultVersionPin.hashSpecificationVersion != descriptor.resultHashSpecificationVersion)
            throw NormalizationResultStoreFailure("RESULT_VERSION_PIN_MISMATCH")
        jdbc.update("""INSERT INTO trading_normalization_result_staging
            (run_id,payload_id,compute_job_id,attempt_token,deletion_generation,transport_sha256,
            byte_size,payload_expires_at,result_delivery_policy_version,content_encoding,
            result_hash,result_schema_version,result_hash_specification_version,state,counts_json,
            section_digests_json,manifest_hash,created_at,updated_at)
            VALUES (?::uuid,?,?,?::uuid,?,?,?,?,?,?,?, ?,?,'RECEIVING',?::jsonb,?::jsonb,?,?,?)
            ON CONFLICT (run_id) DO NOTHING""", run.id.value, descriptor.payloadId, descriptor.jobId,
            descriptor.attemptToken, descriptor.deletionGeneration, descriptor.payloadSha256,
            descriptor.byteSize, Timestamp.from(descriptor.expiresAt),
            descriptor.resultDeliveryPolicyVersion, descriptor.contentEncoding,
            descriptor.resultHash, descriptor.resultSchemaVersion, descriptor.resultHashSpecificationVersion,
            mapper.writeValueAsString(descriptor.counts), mapper.writeValueAsString(descriptor.sectionDigests),
            descriptor.reconciliationManifestHash, Timestamp.from(now), Timestamp.from(now))
        val pinned = jdbc.query("""SELECT payload_id,compute_job_id,attempt_token::text,transport_sha256,
            result_hash,deletion_generation,payload_expires_at,byte_size,
            result_delivery_policy_version,content_encoding
            FROM trading_normalization_result_staging WHERE run_id=?::uuid""",
            { rs, _ -> listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getLong(6).toString(), rs.getTimestamp(7).toInstant().toString(),
                rs.getLong(8).toString(), rs.getString(9), rs.getString(10)) }, run.id.value).single()
        if (pinned != listOf(descriptor.payloadId, descriptor.jobId, descriptor.attemptToken,
                descriptor.payloadSha256, descriptor.resultHash, descriptor.deletionGeneration.toString(),
                descriptor.expiresAt.toString(), descriptor.byteSize.toString(),
                descriptor.resultDeliveryPolicyVersion, descriptor.contentEncoding))
            throw NormalizationResultStoreFailure("RESULT_ITEM_CONFLICT")
    }

    @Suppress("UNCHECKED_CAST")
    override fun loadStageDescriptor(run: NormalizationRun, dispatch: NormalizationDispatch):
        NormalizationResultDescriptor? {
        val identity = requireNotNull(run.inputIdentity)
        return jdbc.query("""SELECT payload_id,compute_job_id,attempt_token::text,
        deletion_generation,transport_sha256,byte_size,payload_expires_at,result_hash,
        result_schema_version,result_hash_specification_version,counts_json::text,
        section_digests_json::text,manifest_hash,result_delivery_policy_version,content_encoding
        FROM trading_normalization_result_staging
        WHERE run_id=?::uuid""", { rs, _ ->
        NormalizationResultDescriptor(rs.getString(2), dispatch.id, run.id.value, rs.getString(1),
            rs.getLong(6), rs.getString(5), rs.getString(3), rs.getLong(4), run.inputFingerprint,
            identity.hashSpecificationVersion,
            identity.targetVersionSet.toWireValue(), rs.getString(9), rs.getString(10),
            mapper.readValue(rs.getString(11), Map::class.java) as Map<String, Any?>,
            mapper.readValue(rs.getString(12), List::class.java) as List<Any?>,
            rs.getString(13), rs.getString(8), rs.getTimestamp(7).toInstant(),
            rs.getString(14) ?: "", rs.getString(15) ?: "")
    }, run.id.value).firstOrNull()
    }

    override fun saveStageBase(runId: String, baseJson: String) {
        check(jdbc.update("""UPDATE trading_normalization_result_staging SET base_json=?::jsonb
            WHERE run_id=?::uuid AND base_json IS NULL AND state='RECEIVING'""",
            baseJson, runId) <= 1)
        val same = jdbc.queryForObject("""SELECT base_json=?::jsonb FROM
            trading_normalization_result_staging WHERE run_id=?::uuid""",
            Boolean::class.java, baseJson, runId)
        if (same != true) throw NormalizationResultStoreFailure("RESULT_ITEM_CONFLICT")
    }

    override fun loadStageBase(runId: String): String? = jdbc.query("""SELECT base_json::text
        FROM trading_normalization_result_staging WHERE run_id=?::uuid""",
        { rs, _ -> rs.getString(1) }, runId).firstOrNull()

    override fun stageItem(runId: String, item: NormalizationStagedItem) {
        val sortKey = sortKey(item.identity)
        jdbc.update("""INSERT INTO trading_normalization_staged_items
            (run_id,section_type,item_identity,item_sort_key,item_sha256,item_json)
            VALUES (?::uuid,?,?,?,?,?::jsonb)
            ON CONFLICT (run_id,section_type,item_identity) DO UPDATE
            SET item_sort_key=EXCLUDED.item_sort_key
            WHERE trading_normalization_staged_items.item_sort_key IS NULL
            AND trading_normalization_staged_items.item_sha256=EXCLUDED.item_sha256""",
            runId, item.sectionType, item.identity, sortKey, item.contentHash, item.publicJson)
        val saved = jdbc.queryForObject("""SELECT item_sha256 FROM trading_normalization_staged_items
            WHERE run_id=?::uuid AND section_type=? AND item_identity=?""", String::class.java,
            runId, item.sectionType, item.identity)
        if (saved != item.contentHash) throw NormalizationResultStoreFailure("RESULT_ITEM_CONFLICT")
    }

    override fun visitItems(runId: String, sectionType: String, batchSize: Int,
                            accept: (NormalizationStagedItem) -> Unit) {
        require(batchSize > 0)
        // V7 can be applied while a V5/V6 stage exists. Repair its sort keys without
        // loading the full stage or guessing a different canonical ordering.
        while (true) {
            val missing = jdbc.query("""SELECT item_identity FROM trading_normalization_staged_items
                WHERE run_id=?::uuid AND section_type=? AND item_sort_key IS NULL
                ORDER BY item_identity COLLATE "C" LIMIT ?""",
                { rs, _ -> rs.getString(1) }, runId, sectionType, batchSize)
            if (missing.isEmpty()) break
            missing.forEach { identity -> jdbc.update("""UPDATE trading_normalization_staged_items
                SET item_sort_key=? WHERE run_id=?::uuid AND section_type=? AND item_identity=?
                AND item_sort_key IS NULL""", sortKey(identity), runId, sectionType, identity) }
        }
        var after: String? = null
        while (true) {
            val page = jdbc.query("""SELECT section_type,item_identity,item_sha256,item_json::text,item_sort_key
                FROM trading_normalization_staged_items WHERE run_id=?::uuid AND section_type=?
                AND (?::text IS NULL OR item_sort_key COLLATE "C">?::text COLLATE "C")
                ORDER BY item_sort_key COLLATE "C" LIMIT ?""", { rs, _ ->
                NormalizationStagedItem(rs.getString(1), rs.getString(2), rs.getString(3),
                    rs.getString(4)) to rs.getString(5)
            }, runId, sectionType, after, after, batchSize)
            if (page.isEmpty()) break
            page.forEach { (item, _) -> accept(item) }
            after = page.last().second
        }
    }

    private fun sortKey(identity: String): String {
        var index = 0
        while (index < identity.length) {
            val current = identity[index]
            if (Character.isHighSurrogate(current)) {
                if (++index >= identity.length || !Character.isLowSurrogate(identity[index]))
                    throw NormalizationResultStoreFailure("RESULT_ITEM_CONFLICT")
            } else if (Character.isLowSurrogate(current)) {
                throw NormalizationResultStoreFailure("RESULT_ITEM_CONFLICT")
            }
            index++
        }
        return HexFormat.of().formatHex(identity.toByteArray(StandardCharsets.UTF_16BE))
    }

    override fun hasInvalidEvidence(run: NormalizationRun): Boolean {
        val input = requireNotNull(run.inputIdentity)
        val artifactIds = mapper.writeValueAsString(input.artifactsByRole.mapValues { it.value.artifactId })
        fun invalid(sql: String, vararg args: Any): Boolean =
            jdbc.queryForObject(sql, Boolean::class.java, *args) == true
        val runId = run.id.value
        if (invalid("""SELECT EXISTS (SELECT 1 FROM trading_normalization_staged_items e
            WHERE e.run_id=?::uuid AND e.section_type='SOURCE_EVIDENCE_SNAPSHOTS'
            AND ((?::jsonb ->> (e.item_json->>'artifactRole')) IS DISTINCT FROM
                    e.item_json->>'coreArtifactId'
                OR (e.item_json->>'sourceRowNumber') !~ '^[1-9][0-9]*$'
                OR (e.item_json->>'sourceRowHash') !~ '^[0-9a-f]{64}$'))""",
                runId, artifactIds)) return true
        if (invalid("""SELECT EXISTS (SELECT 1 FROM trading_normalization_staged_items r
            LEFT JOIN trading_normalization_staged_items e ON e.run_id=r.run_id
                AND e.section_type='SOURCE_EVIDENCE_SNAPSHOTS'
                AND e.item_identity=r.item_json->>'sourceEvidenceSnapshotId'
            WHERE r.run_id=?::uuid AND r.section_type='CANONICAL_RECORDS'
            AND (e.item_identity IS NULL OR
                (r.item_json->>'recordHash') !~ '^[0-9a-f]{64}$'))""", runId)) return true
        if (invalid("""SELECT EXISTS (SELECT 1 FROM trading_normalization_staged_items u
            CROSS JOIN LATERAL jsonb_array_elements(u.item_json->'allocations') a(value)
            LEFT JOIN trading_normalization_staged_items r ON r.run_id=u.run_id
                AND r.section_type='CANONICAL_RECORDS'
                AND r.item_identity=a.value->>'recordId'
            WHERE u.run_id=?::uuid AND u.section_type='REVIEW_UNITS'
            AND (r.item_identity IS NULL OR a.value->>'reviewUnitId' IS DISTINCT FROM u.item_identity
                OR NULLIF(a.value->>'allocationId','') IS NULL))""", runId)) return true
        if (invalid("""SELECT EXISTS (SELECT 1 FROM (
                SELECT a.value->>'allocationId' AS id
                FROM trading_normalization_staged_items u
                CROSS JOIN LATERAL jsonb_array_elements(u.item_json->'allocations') a(value)
                WHERE u.run_id=?::uuid AND u.section_type='REVIEW_UNITS'
                GROUP BY a.value->>'allocationId' HAVING count(*)>1) duplicate)""", runId)) return true
        if (invalid("""SELECT EXISTS (SELECT 1 FROM trading_normalization_staged_items m
            WHERE m.run_id=?::uuid AND m.section_type='RECONCILIATION_MATCHES'
            AND (NOT EXISTS (SELECT 1 FROM trading_normalization_staged_items r
                    WHERE r.run_id=m.run_id AND r.section_type='CANONICAL_RECORDS'
                    AND r.item_identity=m.item_json->>'summaryRecordId')
                OR (m.item_json->>'reviewUnitId' IS NOT NULL AND NOT EXISTS
                    (SELECT 1 FROM trading_normalization_staged_items u WHERE u.run_id=m.run_id
                    AND u.section_type='REVIEW_UNITS' AND u.item_identity=m.item_json->>'reviewUnitId'))
                OR EXISTS (SELECT 1 FROM jsonb_array_elements_text(
                    m.item_json->'candidateReviewUnitIds') candidate(id) WHERE NOT EXISTS
                    (SELECT 1 FROM trading_normalization_staged_items u WHERE u.run_id=m.run_id
                    AND u.section_type='REVIEW_UNITS' AND u.item_identity=candidate.id))
                OR EXISTS (SELECT 1 FROM jsonb_array_elements_text(
                    m.item_json->'evidenceRecordIds') evidence(id) WHERE NOT EXISTS
                    (SELECT 1 FROM trading_normalization_staged_items r WHERE r.run_id=m.run_id
                    AND r.section_type='CANONICAL_RECORDS' AND r.item_identity=evidence.id))))""",
                runId)) return true
        return invalid("""SELECT EXISTS (SELECT 1 FROM trading_normalization_staged_items i
            WHERE i.run_id=?::uuid AND i.section_type='RECONCILIATION_ISSUES'
            AND ((i.item_json->>'reviewUnitId' IS NOT NULL AND NOT EXISTS
                    (SELECT 1 FROM trading_normalization_staged_items u WHERE u.run_id=i.run_id
                    AND u.section_type='REVIEW_UNITS' AND u.item_identity=i.item_json->>'reviewUnitId'))
                OR (i.item_json->>'summaryRecordId' IS NOT NULL AND NOT EXISTS
                    (SELECT 1 FROM trading_normalization_staged_items r WHERE r.run_id=i.run_id
                    AND r.section_type='CANONICAL_RECORDS' AND r.item_identity=i.item_json->>'summaryRecordId'))
                OR EXISTS (SELECT 1 FROM jsonb_array_elements_text(
                    i.item_json->'evidenceRecordIds') evidence(id) WHERE NOT EXISTS
                    (SELECT 1 FROM trading_normalization_staged_items r WHERE r.run_id=i.run_id
                    AND r.section_type='CANONICAL_RECORDS' AND r.item_identity=evidence.id))))""",
            runId)
    }

    override fun hasInvalidSupplement(runId: String): Boolean {
        fun invalid(sql: String): Boolean = jdbc.queryForObject(sql, Boolean::class.java,
            runId, runId) == true
        if (invalid("""WITH supplements AS (
                SELECT * FROM trading_normalization_staged_items
                WHERE run_id=?::uuid AND section_type='ANALYTICS_INPUT_SUPPLEMENT_ROWS'),
            units AS (SELECT * FROM trading_normalization_staged_items
                WHERE run_id=?::uuid AND section_type='REVIEW_UNITS')
            SELECT EXISTS (SELECT 1 FROM supplements s FULL JOIN units u
                ON s.item_identity=u.item_identity
                WHERE s.item_identity IS NULL OR u.item_identity IS NULL
                OR (s.item_json->>'stableEpisodeTimeAvailable')::boolean IS DISTINCT FROM
                    jsonb_exists(u.item_json->'capability', 'STABLE_EPISODE_TIME')
                OR EXISTS (SELECT 1 FROM jsonb_array_elements_text(
                    s.item_json->'evidenceRecordIds') evidence(id)
                    LEFT JOIN trading_normalization_staged_items r
                    ON r.run_id=s.run_id AND r.section_type='CANONICAL_RECORDS'
                    AND r.item_identity=evidence.id
                    WHERE r.item_identity IS NULL OR r.item_json->>'instrumentId'
                        IS DISTINCT FROM u.item_json->>'instrumentId')
                OR EXISTS (SELECT 1 FROM jsonb_array_elements_text(
                    s.item_json->'evidenceRecordIds') evidence(id)
                    JOIN trading_normalization_staged_items r ON r.run_id=s.run_id
                        AND r.section_type='CANONICAL_RECORDS' AND r.item_identity=evidence.id
                    WHERE r.item_json->>'recordType'='VENUE_POSITION_SUMMARY'
                    AND NOT EXISTS (SELECT 1 FROM trading_normalization_staged_items explanation
                        WHERE explanation.run_id=s.run_id
                        AND ((explanation.section_type='RECONCILIATION_MATCHES'
                            AND explanation.item_json->>'summaryRecordId'=evidence.id
                            AND (explanation.item_json->>'reviewUnitId'=s.item_identity
                                OR jsonb_exists(explanation.item_json->'candidateReviewUnitIds',
                                    s.item_identity)))
                        OR (explanation.section_type='RECONCILIATION_ISSUES'
                            AND explanation.item_json->>'summaryRecordId'=evidence.id
                            AND explanation.item_json->>'reviewUnitId'=s.item_identity)))))"""))
            return true
        return invalid("""WITH supplement AS (
                SELECT * FROM trading_normalization_staged_items
                WHERE run_id=?::uuid AND section_type='ANALYTICS_INPUT_SUPPLEMENT_ROWS'),
            extras AS (SELECT s.run_id,s.item_identity AS unit_id,a.value AS extra
                FROM supplement s CROSS JOIN LATERAL
                    jsonb_array_elements(s.item_json->'allocationEvidence') a),
            allocations AS (SELECT u.run_id,u.item_identity AS unit_id,a.value AS public
                FROM trading_normalization_staged_items u CROSS JOIN LATERAL
                    jsonb_array_elements(u.item_json->'allocations') a
                WHERE u.run_id=?::uuid AND u.section_type='REVIEW_UNITS')
            SELECT EXISTS (SELECT 1 FROM extras e FULL JOIN allocations a
                ON e.unit_id=a.unit_id AND e.extra->>'allocationId'=a.public->>'allocationId'
                LEFT JOIN trading_normalization_staged_items execution
                    ON execution.run_id=COALESCE(e.run_id,a.run_id)
                    AND execution.section_type='CANONICAL_RECORDS'
                    AND execution.item_identity=a.public->>'recordId'
                WHERE e.extra IS NULL OR a.public IS NULL OR execution.item_identity IS NULL
                OR execution.item_json->>'recordType'<>'EXECUTION'
                OR (a.public->'allocatedFee' IS NOT NULL
                    AND a.public->'allocatedFee'<>'null'::jsonb
                    AND a.public->'allocatedFee'<>e.extra->'allocatedFee')
                OR (a.public->'allocatedReportedPnl' IS NOT NULL
                    AND a.public->'allocatedReportedPnl'<>'null'::jsonb
                    AND a.public->'allocatedReportedPnl'<>e.extra->'allocatedReportedPnl')
                OR EXISTS (SELECT 1 FROM jsonb_array_elements_text(
                    e.extra->'evidenceRecordIds') evidence(id)
                    LEFT JOIN trading_normalization_staged_items r ON r.run_id=e.run_id
                    AND r.section_type='CANONICAL_RECORDS' AND r.item_identity=evidence.id
                    WHERE r.item_identity IS NULL OR r.item_json->>'instrumentId'
                        IS DISTINCT FROM execution.item_json->>'instrumentId'
                    OR r.item_json->>'sourceExecutionKey'
                        IS DISTINCT FROM execution.item_json->>'sourceExecutionKey')
                OR (SELECT count(DISTINCT r.item_json->>'recordType') FROM
                    jsonb_array_elements_text(e.extra->'evidenceRecordIds') evidence(id)
                    JOIN trading_normalization_staged_items r ON r.run_id=e.run_id
                    AND r.section_type='CANONICAL_RECORDS' AND r.item_identity=evidence.id)<>3
                OR EXISTS (SELECT 1 FROM jsonb_array_elements_text(
                    e.extra->'evidenceRecordIds') evidence(id)
                    JOIN trading_normalization_staged_items r ON r.run_id=e.run_id
                    AND r.section_type='CANONICAL_RECORDS' AND r.item_identity=evidence.id
                    WHERE r.item_json->>'recordType' NOT IN
                        ('EXECUTION','FEE','VENUE_REPORTED_PNL'))
                OR NOT jsonb_exists(e.extra->'evidenceRecordIds', a.public->>'recordId')
                OR EXISTS (SELECT 1 FROM jsonb_array_elements_text(
                    e.extra->'evidenceRecordIds') evidence(id)
                    JOIN trading_normalization_staged_items r ON r.run_id=e.run_id
                    AND r.section_type='CANONICAL_RECORDS' AND r.item_identity=evidence.id
                    WHERE (r.item_json->>'recordType'='FEE' AND
                        r.item_json->'sourceAmount'->>'currency'
                        IS DISTINCT FROM e.extra->'allocatedFee'->>'currency')
                    OR (r.item_json->>'recordType'='VENUE_REPORTED_PNL' AND
                        r.item_json->'realizedPnl'->>'currency'
                        IS DISTINCT FROM e.extra->'allocatedReportedPnl'->>'currency'))
            )""")
    }

    override fun stagedCounts(runId: String): Map<String, Long> {
        val sections = jdbc.query("""SELECT section_type,count(*) FROM trading_normalization_staged_items
            WHERE run_id=?::uuid GROUP BY section_type""", { rs, _ -> rs.getString(1) to rs.getLong(2) },
            runId).toMap().toMutableMap()
        val nested = jdbc.query("""SELECT
            COALESCE(SUM(jsonb_array_length(item_json->'allocations')),0),
            COALESCE(SUM(CASE WHEN jsonb_array_length(item_json->'exclusionReasons')>0 THEN 1 ELSE 0 END),0)
            FROM trading_normalization_staged_items
            WHERE run_id=?::uuid AND section_type='REVIEW_UNITS'""", { rs, _ ->
            rs.getLong(1) to rs.getLong(2) }, runId).single()
        sections["POSITION_ALLOCATIONS"] = nested.first
        sections["EXCLUDED_REVIEW_UNITS"] = nested.second
        val nestedValidationIssues = jdbc.queryForObject("""SELECT COALESCE(SUM(
            jsonb_array_length(item_json->'validationIssues')),0)
            FROM trading_normalization_staged_items
            WHERE run_id=?::uuid AND section_type='QUALITY_REPORT'""", Long::class.java, runId) ?: 0
        sections["VALIDATION_ISSUES"] = (sections["VALIDATION_ISSUES"] ?: 0L) + nestedValidationIssues
        return sections
    }

    override fun markValidated(runId: String, now: Instant) {
        check(jdbc.update("""UPDATE trading_normalization_result_staging SET state='VALIDATED',
            validated_at=?,updated_at=? WHERE run_id=?::uuid AND state IN ('RECEIVING','VALIDATED')""",
            Timestamp.from(now), Timestamp.from(now), runId) == 1)
    }

    override fun discardStage(runId: String) {
        jdbc.update("DELETE FROM trading_normalization_result_staging WHERE run_id=?::uuid", runId)
    }

    override fun cleanupObsoleteStages(limit: Int): Int = jdbc.query("""WITH obsolete AS (
        SELECT stage.run_id FROM trading_normalization_result_staging stage
        JOIN trading_normalization_runs r ON r.id=stage.run_id
        JOIN trading_import_sessions s ON s.id=r.import_session_id
        JOIN trading_books b ON b.id=r.trading_book_id
        WHERE r.status IN ('TERMINAL_FAILURE','OBSOLETE','PUBLISHED')
           OR s.deletion_pending OR s.deletion_generation<>r.deletion_generation
           OR b.status='DELETION_PENDING'
        ORDER BY stage.updated_at LIMIT ? FOR UPDATE OF stage SKIP LOCKED)
        DELETE FROM trading_normalization_result_staging stage USING obsolete
        WHERE stage.run_id=obsolete.run_id RETURNING stage.run_id""", { rs, _ -> rs.getString(1) },
        limit).size

    override fun cleanupAcknowledgementsForDeletedSessions(limit: Int): Int = jdbc.query("""
        WITH cleared AS (SELECT a.id FROM trading_normalization_ack_dispatches a
            WHERE a.status<>'PENDING' AND NOT EXISTS
                (SELECT 1 FROM trading_import_sessions s WHERE s.id=a.core_import_session_id)
            ORDER BY a.updated_at LIMIT ? FOR UPDATE OF a SKIP LOCKED)
        DELETE FROM trading_normalization_ack_dispatches a USING cleared
        WHERE a.id=cleared.id RETURNING a.id""", { rs, _ -> rs.getString(1) }, limit).size

    override fun publish(run: NormalizationRun, dispatch: NormalizationDispatch, workerId: String,
                         descriptor: NormalizationResultDescriptor, manifestJson: String,
                         coverageJson: String, capabilityJson: String, qualityJson: String,
                         ledgerContentHash: String, now: Instant): Boolean {
        if (run.resultVersionPin != dispatch.resultVersionPin ||
            run.resultVersionPin.schemaVersion != descriptor.resultSchemaVersion ||
            run.resultVersionPin.hashSpecificationVersion != descriptor.resultHashSpecificationVersion)
            throw NormalizationResultStoreFailure("RESULT_VERSION_PIN_MISMATCH")
        val session = jdbc.query("""SELECT status,deletion_generation,deletion_pending,
            active_normalization_run_id::text,created_ledger_revision_id::text FROM trading_import_sessions
            WHERE id=? AND owner_member_id=? FOR UPDATE""", { rs, _ ->
            listOf(rs.getString(1), rs.getLong(2).toString(), rs.getBoolean(3).toString(),
                rs.getString(4) ?: "", rs.getString(5) ?: "")
        }, run.input.importSessionId.value, run.input.ownerId).firstOrNull() ?: return false
        if (session[0] == "ACCEPTED" && session[4].isNotBlank()) {
            val existing = jdbc.query("""SELECT run_id::text,result_hash FROM trading_ledger_revisions
                WHERE id=?::uuid""", { rs, _ -> rs.getString(1) to rs.getString(2) },
                session[4]).firstOrNull()
            return existing == (run.id.value to descriptor.resultHash)
        }
        if (session != listOf("VALIDATING", run.input.deletionGeneration.toString(), "false", run.id.value, ""))
            return false
        val book = jdbc.query("""SELECT status,latest_ledger_revision_id::text FROM trading_books
            WHERE id=? AND owner_member_id=? FOR UPDATE""", { rs, _ ->
            rs.getString(1) to rs.getString(2)
        }, run.input.tradingBookId.value, run.input.ownerId).firstOrNull() ?: return false
        if (book.first != "ACTIVE") return false
        val current = jdbc.query("""SELECT r.status,r.attempt_token::text,r.compute_job_id,r.input_fingerprint,
            r.deletion_generation,d.status,d.lease_owner::text,d.lease_expires_at,
            s.state FROM trading_normalization_runs r JOIN trading_normalization_dispatches d ON d.run_id=r.id
            JOIN trading_normalization_result_staging s ON s.run_id=r.id
            WHERE r.id=?::uuid AND d.id=?::uuid FOR UPDATE OF r,d,s""", { rs, _ ->
            listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getLong(5).toString(), rs.getString(6), rs.getString(7) ?: "",
                rs.getTimestamp(8)?.toInstant()?.toString() ?: "", rs.getString(9))
        }, run.id.value, dispatch.id).firstOrNull() ?: return false
        if (current[0] != "RESULT_READY" || current[1] != descriptor.attemptToken ||
            current[2] != descriptor.jobId || current[3] != descriptor.inputSetHash ||
            current[4] != descriptor.deletionGeneration.toString() || current[5] != "RESULT_READY" ||
            current[6] != workerId || current[7].isBlank() ||
            !Instant.parse(current[7]).isAfter(now) || current[8] != "VALIDATED") return false
        val number = (jdbc.queryForObject("""SELECT COALESCE(MAX(revision_number),0)+1
            FROM trading_ledger_revisions WHERE trading_book_id=?""", Long::class.java,
            run.input.tradingBookId.value) ?: 1L)
        val revisionId = UUID.randomUUID().toString()
        val identity = requireNotNull(run.inputIdentity)
        // Materialize published nested collections inside PostgreSQL. No complete manifest or
        // quality-report collection is returned to the Core JVM during publication.
        fun stageArray(section: String) = """COALESCE((SELECT jsonb_agg(item_json
            ORDER BY item_sort_key COLLATE "C") FROM trading_normalization_staged_items
            WHERE run_id=?::uuid AND section_type='$section'), '[]'::jsonb)"""
        val manifestProjection = """jsonb_set(jsonb_set(jsonb_set(?::jsonb,
            '{reviewUnits}', ${stageArray("REVIEW_UNITS")}),
            '{reconciliationMatches}', ${stageArray("RECONCILIATION_MATCHES")}),
            '{reconciliationIssues}', ${stageArray("RECONCILIATION_ISSUES")})"""
        val qualityProjection = """jsonb_set(?::jsonb, '{validationIssues}',
            ${stageArray("VALIDATION_ISSUES")})"""
        jdbc.update("""INSERT INTO trading_ledger_revisions
            (id,run_id,trading_book_id,import_session_id,revision_number,input_set_hash,
            input_hash_specification_version,input_identity_json,target_version_set_json,
            result_schema_version,result_hash_specification_version,result_hash,ledger_content_hash,
            reconciliation_manifest_hash,reconciliation_manifest_json,coverage_json,capability_json,
            quality_report_json,counts_json,section_digests_json,published_at)
            VALUES (?::uuid,?::uuid,?,?,?,?,?,?::jsonb,?::jsonb,?,?,?,?,?,
            $manifestProjection,?::jsonb,?::jsonb,$qualityProjection,?::jsonb,?::jsonb,?)""",
            revisionId, run.id.value, run.input.tradingBookId.value, run.input.importSessionId.value, number,
            run.inputFingerprint, identity.hashSpecificationVersion,
            mapper.writeValueAsString(identity.toWireValue()),
            mapper.writeValueAsString(identity.targetVersionSet.toWireValue()),
            descriptor.resultSchemaVersion, descriptor.resultHashSpecificationVersion,
            descriptor.resultHash, ledgerContentHash, descriptor.reconciliationManifestHash,
            manifestJson, run.id.value, run.id.value, run.id.value, coverageJson, capabilityJson,
            qualityJson, run.id.value,
            mapper.writeValueAsString(descriptor.counts), mapper.writeValueAsString(descriptor.sectionDigests),
            Timestamp.from(now))
        jdbc.update("""INSERT INTO trading_source_evidence_snapshots
            (trading_book_id,import_session_id,snapshot_id,source_artifact_id,source_row_number,
            evidence_schema_version,masking_policy_version,evidence_json)
            SELECT ?,?,item_identity,(item_json->>'coreArtifactId')::bigint,
            (item_json->>'sourceRowNumber')::bigint,?,item_json->>'maskingPolicyVersion',item_json
            FROM trading_normalization_staged_items WHERE run_id=?::uuid
            AND section_type='SOURCE_EVIDENCE_SNAPSHOTS' ON CONFLICT DO NOTHING""",
            run.input.tradingBookId.value, run.input.importSessionId.value,
            identity.targetVersionSet.reconstruction.evidenceSchemaVersion, run.id.value)
        val evidenceConflicts = jdbc.queryForObject("""SELECT count(*) FROM trading_normalization_staged_items i
            LEFT JOIN trading_source_evidence_snapshots e ON e.trading_book_id=? AND e.snapshot_id=i.item_identity
            WHERE i.run_id=?::uuid AND i.section_type='SOURCE_EVIDENCE_SNAPSHOTS'
            AND (e.snapshot_id IS NULL OR e.evidence_json<>i.item_json)""",
            Long::class.java, run.input.tradingBookId.value, run.id.value) ?: 0
        if (evidenceConflicts != 0L) throw NormalizationResultStoreFailure("RESULT_ITEM_CONFLICT")
        jdbc.update("""INSERT INTO trading_records
            (trading_book_id,record_id,source_evidence_snapshot_id,record_hash,record_json)
            SELECT ?,item_identity,item_json->>'sourceEvidenceSnapshotId',
            item_json->>'recordHash',item_json
            FROM trading_normalization_staged_items WHERE run_id=?::uuid
            AND section_type='CANONICAL_RECORDS' ON CONFLICT DO NOTHING""",
            run.input.tradingBookId.value, run.id.value)
        val recordConflicts = jdbc.queryForObject("""SELECT count(*) FROM trading_normalization_staged_items i
            LEFT JOIN trading_records r ON r.trading_book_id=? AND r.record_id=i.item_identity
            WHERE i.run_id=?::uuid AND i.section_type='CANONICAL_RECORDS'
            AND (r.record_id IS NULL OR r.record_json<>i.item_json)""",
            Long::class.java, run.input.tradingBookId.value, run.id.value) ?: 0
        if (recordConflicts != 0L) throw NormalizationResultStoreFailure("RESULT_ITEM_CONFLICT")
        jdbc.update("""INSERT INTO trading_ledger_revision_records
            (revision_id,trading_book_id,record_id,semantic_order_key)
            SELECT ?::uuid,?,item_identity,item_identity
            FROM trading_normalization_staged_items WHERE run_id=?::uuid
            AND section_type='CANONICAL_RECORDS'""", revisionId, run.input.tradingBookId.value, run.id.value)
        jdbc.update("""INSERT INTO trading_ledger_review_units(revision_id,review_unit_id,unit_json)
            SELECT ?::uuid,item_identity,item_json FROM trading_normalization_staged_items
            WHERE run_id=?::uuid AND section_type='REVIEW_UNITS'""", revisionId, run.id.value)
        if (descriptor.resultSchemaVersion == "TRADING_NORMALIZATION_RESULT_V2") {
            jdbc.update("""INSERT INTO trading_ledger_analytics_supplement
                (revision_id,review_unit_id,engine_input_json)
                SELECT ?::uuid,item_identity,item_json FROM trading_normalization_staged_items
                WHERE run_id=?::uuid AND section_type='ANALYTICS_INPUT_SUPPLEMENT_ROWS'""",
                revisionId, run.id.value)
            val savedCount = jdbc.queryForObject("""SELECT count(*) FROM
                trading_ledger_analytics_supplement WHERE revision_id=?::uuid""",
                Long::class.java, revisionId)
            if (savedCount != descriptor.counts["reviewUnitCount"]?.toString()?.toLongOrNull())
                throw NormalizationResultStoreFailure("RESULT_COUNT_MISMATCH")
        }
        jdbc.update("""INSERT INTO trading_ledger_allocations
            (revision_id,allocation_id,review_unit_id,record_id,allocation_json)
            SELECT ?::uuid,a.value->>'allocationId',u.item_identity,a.value->>'recordId',a.value
            FROM trading_normalization_staged_items u
            CROSS JOIN LATERAL jsonb_array_elements(u.item_json->'allocations') a(value)
            WHERE u.run_id=?::uuid AND u.section_type='REVIEW_UNITS'""", revisionId, run.id.value)
        check(jdbc.update("""UPDATE trading_import_sessions SET status='ACCEPTED',
            created_ledger_revision_id=?::uuid,failure_code=NULL,failure_retryable=NULL,
            failure_user_action=NULL,failure_occurred_at=NULL,updated_at=?,version=version+1
            WHERE id=? AND status='VALIDATING' AND active_normalization_run_id=?::uuid
            AND deletion_generation=? AND NOT deletion_pending""", revisionId, Timestamp.from(now),
            run.input.importSessionId.value, run.id.value, run.input.deletionGeneration) == 1)
        jdbc.update("""UPDATE trading_books SET latest_ledger_revision_id=?::uuid,
            updated_at=?,version=version+1 WHERE id=?""", revisionId, Timestamp.from(now),
            run.input.tradingBookId.value)
        jdbc.update("""UPDATE trading_normalization_runs SET status='PUBLISHED',
            completed_at=?,updated_at=?,version=version+1 WHERE id=?::uuid""",
            Timestamp.from(now), Timestamp.from(now), run.id.value)
        jdbc.update("""UPDATE trading_normalization_attempts SET completed_at=?,result_category='PUBLISHED'
            WHERE run_id=?::uuid AND attempt_token=?::uuid AND completed_at IS NULL""",
            Timestamp.from(now), run.id.value, descriptor.attemptToken)
        jdbc.update("""UPDATE trading_normalization_dispatches SET status='PUBLISHED',
            lease_owner=NULL,lease_expires_at=NULL,updated_at=? WHERE id=?::uuid""",
            Timestamp.from(now), dispatch.id)
        enqueueAck(run, descriptor, "PERSISTED", null, now)
        jdbc.update("""INSERT INTO trading_normalization_audit(run_id,event,occurred_at)
            VALUES (?::uuid,'LEDGER_PUBLISHED',?)""", run.id.value, Timestamp.from(now))
        jdbc.update("DELETE FROM trading_normalization_result_staging WHERE run_id=?::uuid", run.id.value)
        return true
    }

    private fun enqueueAck(run: NormalizationRun, descriptor: NormalizationResultDescriptor,
                           disposition: String, currentGeneration: Long?, now: Instant) {
        val key = java.security.MessageDigest.getInstance("SHA-256").digest(
            "${run.id.value}:${descriptor.jobId}:${descriptor.payloadId}:$disposition".toByteArray(Charsets.UTF_8))
            .let { java.util.HexFormat.of().formatHex(it) }
        jdbc.update("""INSERT INTO trading_normalization_ack_dispatches
            (id,run_id,core_import_session_id,compute_job_id,payload_id,payload_sha256,result_hash,attempt_token,
            deletion_generation,current_deletion_generation,disposition,idempotency_key,retry_policy_json,
            payload_expires_at,status,
            next_attempt_at,created_at,updated_at)
            VALUES (?::uuid,?::uuid,?,?,?,?,?,?::uuid,?,?,?,?,?::jsonb,?,'PENDING',?,?,?)
            ON CONFLICT (idempotency_key) DO NOTHING""", UUID.randomUUID().toString(), run.id.value,
            run.input.importSessionId.value,
            descriptor.jobId, descriptor.payloadId, descriptor.payloadSha256, descriptor.resultHash,
            descriptor.attemptToken, descriptor.deletionGeneration, currentGeneration, disposition,
            key, run.input.retryPolicyJson, Timestamp.from(descriptor.expiresAt),
            Timestamp.from(now), Timestamp.from(now), Timestamp.from(now))
    }

    override fun discardStale(run: NormalizationRun, dispatch: NormalizationDispatch,
                              workerId: String, descriptor: NormalizationResultDescriptor,
                              now: Instant): Boolean {
        val generation = jdbc.query("""SELECT GREATEST(s.deletion_generation,
            CASE WHEN b.status='DELETION_PENDING' THEN b.deletion_generation ELSE 0 END,
            CASE WHEN a.status='DELETION_PENDING' THEN a.deletion_generation ELSE 0 END)
            FROM trading_import_sessions s JOIN trading_books b ON b.id=s.trading_book_id
            JOIN trading_accounts a ON a.id=b.account_id WHERE s.id=? AND s.owner_member_id=?
            FOR UPDATE OF s,b,a""", { rs, _ -> rs.getLong(1) },
            run.input.importSessionId.value, run.input.ownerId).firstOrNull() ?: return false
        if (generation <= run.input.deletionGeneration ||
            descriptor.runId != run.id.value || descriptor.jobId != run.computeJobId ||
            descriptor.attemptToken != run.attemptToken ||
            descriptor.deletionGeneration != run.input.deletionGeneration ||
            descriptor.inputSetHash != run.inputFingerprint) return false
        val changed = jdbc.update("""UPDATE trading_normalization_dispatches SET status='OBSOLETE',
            lease_owner=NULL,lease_expires_at=NULL,updated_at=? WHERE id=?::uuid AND run_id=?::uuid
            AND lease_owner=?::uuid AND lease_expires_at>? AND status='RESULT_READY'""",
            Timestamp.from(now), dispatch.id, run.id.value, workerId, Timestamp.from(now))
        if (changed != 1) return false
        jdbc.update("""UPDATE trading_normalization_runs SET status='OBSOLETE',completed_at=?,
            updated_at=?,version=version+1 WHERE id=?::uuid AND status='RESULT_READY'
            AND attempt_token=?::uuid AND compute_job_id=?""", Timestamp.from(now),
            Timestamp.from(now), run.id.value, descriptor.attemptToken, descriptor.jobId).also { check(it == 1) }
        jdbc.update("""UPDATE trading_normalization_attempts SET completed_at=?,
            result_category='OBSOLETE' WHERE run_id=?::uuid AND attempt_token=?::uuid
            AND completed_at IS NULL""", Timestamp.from(now), run.id.value, descriptor.attemptToken)
        enqueueAck(run, descriptor, "DISCARDED_STALE", generation, now)
        discardStage(run.id.value)
        jdbc.update("""INSERT INTO trading_normalization_audit(run_id,event,occurred_at)
            VALUES (?::uuid,'STALE_RESULT_DISCARDED',?)""", run.id.value, Timestamp.from(now))
        return true
    }

    override fun claimAck(now: Instant, lease: Duration, workerId: String): NormalizationPayloadAcknowledgement? =
        jdbc.query("""WITH due AS (SELECT id FROM trading_normalization_ack_dispatches
            WHERE status='PENDING' AND next_attempt_at<=? AND
            (lease_expires_at IS NULL OR lease_expires_at<=?)
            ORDER BY next_attempt_at,id LIMIT 1 FOR UPDATE SKIP LOCKED)
            UPDATE trading_normalization_ack_dispatches a SET lease_owner=?::uuid,
            lease_expires_at=?,attempt_count=attempt_count+1,updated_at=?
            FROM due WHERE a.id=due.id RETURNING a.*""", { rs, _ ->
            NormalizationPayloadAcknowledgement(rs.getString("run_id"),
                rs.getLong("core_import_session_id").toString(), rs.getString("compute_job_id"),
                rs.getString("payload_id"), rs.getString("payload_sha256"), rs.getString("result_hash"),
                rs.getString("attempt_token"), rs.getLong("deletion_generation"),
                rs.getString("idempotency_key"), rs.getString("disposition"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getLong("current_deletion_generation").takeUnless { rs.wasNull() },
                rs.getInt("attempt_count"), rs.getString("retry_policy_json"),
                rs.getTimestamp("payload_expires_at").toInstant())
        }, Timestamp.from(now), Timestamp.from(now), workerId, Timestamp.from(now.plus(lease)),
            Timestamp.from(now)).firstOrNull()

    override fun completeAck(key: String, workerId: String, now: Instant): Boolean = jdbc.update("""
        UPDATE trading_normalization_ack_dispatches SET status='ACKNOWLEDGED',
        lease_owner=NULL,lease_expires_at=NULL,updated_at=? WHERE idempotency_key=?
        AND lease_owner=?::uuid AND lease_expires_at>? AND status='PENDING'""",
        Timestamp.from(now), key, workerId, Timestamp.from(now)) == 1

    override fun retryAck(key: String, workerId: String, next: Instant, now: Instant): Boolean = jdbc.update("""
        UPDATE trading_normalization_ack_dispatches SET next_attempt_at=?,
        last_failure_code='TEMPORARY_DEPENDENCY_FAILURE',lease_owner=NULL,
        lease_expires_at=NULL,updated_at=? WHERE idempotency_key=?
        AND lease_owner=?::uuid AND lease_expires_at>? AND status='PENDING'""",
        Timestamp.from(next), Timestamp.from(now), key, workerId, Timestamp.from(now)) == 1

    override fun failAck(key: String, workerId: String, code: String, now: Instant): Boolean = jdbc.update("""
        UPDATE trading_normalization_ack_dispatches SET status='TERMINAL_FAILURE',
        last_failure_code=?,lease_owner=NULL,lease_expires_at=NULL,updated_at=?
        WHERE idempotency_key=? AND lease_owner=?::uuid AND lease_expires_at>?
        AND status='PENDING'""", code, Timestamp.from(now), key, workerId, Timestamp.from(now)) == 1

    private fun dispatch(rs: ResultSet) = NormalizationDispatch(rs.getString("id"), rs.getString("run_id"),
        rs.getString("compute_key"), NormalizationDispatchStatus.valueOf(rs.getString("status")),
        rs.getInt("attempt_count"), rs.getTimestamp("next_attempt_at").toInstant(),
        rs.getString("lease_owner"), rs.getTimestamp("lease_expires_at")?.toInstant(),
        rs.getString("last_failure_code"), rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant(), rs.getInt("poll_failure_count"),
        NormalizationResultVersionPin(rs.getString("result_schema_version"),
            rs.getString("result_hash_specification_version")))
}
