package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.LedgerRevision
import com.refinvest.core.tradingreview.domain.importsession.NormalizationRun
import com.refinvest.core.tradingreview.domain.valueobject.*
import com.refinvest.core.tradingreview.port.*
import com.refinvest.core.tradingreview.port.ledger.*
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

@Repository
class JdbcLedgerRevisionPersistenceAdapter(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
    private val clock: Clock,
    private val transition: TradingNormalizationTransitionGate,
    private val staging: TradingNormalizationResultStore,
) : LedgerRevisionStore, TradingLedgerPublication {
    override fun find(id: LedgerRevisionId, owner: MemberId): LedgerRevision? = jdbc.query(
        LedgerRevisionRows.SELECT + " WHERE r.id=?::uuid AND s.owner_member_id=? AND s.status='ACCEPTED'" +
            " AND NOT s.deletion_pending AND b.status='ACTIVE' AND a.status='ACTIVE'",
        { rs, _ -> LedgerRevisionPersistenceMapper.toDomain(LedgerRevisionRows.read(rs)) },
        id.value.toString(), owner.value).firstOrNull()

    @Transactional
    override fun publish(publication: LedgerPublication): Boolean {
        check(jdbc.execute(ConnectionCallback { !it.autoCommit }) == true) { "Publication requires an enclosing transaction" }
        if (!transition.publicationAllowed()) return false
        val candidate = publication.revision
        val run = publication.run
        val dispatch = publication.dispatch
        val descriptor = publication.descriptor
        val lease = publication.lease
        require(candidate.publication == null)
        if (run.resultVersionPin != dispatch.resultVersionPin || candidate.result.versionPin != run.resultVersionPin ||
            run.resultVersionPin.schemaVersion != descriptor.resultSchemaVersion ||
            run.resultVersionPin.hashSpecificationVersion != descriptor.resultHashSpecificationVersion)
            throw NormalizationResultStoreFailure("RESULT_VERSION_PIN_MISMATCH")
        if (!bound(publication)) return false
        val accountId = jdbc.query("SELECT account_id FROM trading_books WHERE id=? AND owner_member_id=?",
            { rs, _ -> rs.getLong(1) }, candidate.bookId.value, candidate.owner.value).firstOrNull() ?: return false
        val account = jdbc.query("SELECT status FROM trading_accounts WHERE id=? AND owner_member_id=? FOR UPDATE",
            { rs, _ -> rs.getString(1) }, accountId, candidate.owner.value).firstOrNull()
        if (account != "ACTIVE") return false
        val book = jdbc.query("SELECT status,version FROM trading_books WHERE id=? AND account_id=? AND owner_member_id=? FOR UPDATE",
            { rs, _ -> rs.getString(1) to rs.getLong(2) }, candidate.bookId.value, accountId, candidate.owner.value).firstOrNull() ?: return false
        if (book.first != "ACTIVE") return false
        val session = jdbc.query("""SELECT status,deletion_generation,deletion_pending,
            active_normalization_run_id::text,created_ledger_revision_id::text FROM trading_import_sessions
            WHERE id=? AND owner_member_id=? AND trading_book_id=? FOR UPDATE""", { rs, _ ->
            listOf(rs.getString(1), rs.getLong(2).toString(), rs.getBoolean(3).toString(),
                rs.getString(4) ?: "", rs.getString(5) ?: "") }, candidate.importSessionId.value,
            candidate.owner.value, candidate.bookId.value).firstOrNull() ?: return false
        if (session[2] != "false" || session[1] != run.input.deletionGeneration.toString()) return false
        if (session[0] == "ACCEPTED" && session[4].isNotBlank()) {
            val existing = find(LedgerRevisionId(java.util.UUID.fromString(session[4])), candidate.owner) ?: return false
            return sameContent(existing, candidate) && jdbc.queryForObject("""SELECT EXISTS (
                SELECT 1 FROM trading_normalization_ack_dispatches WHERE run_id=?::uuid
                AND compute_job_id=? AND payload_id=? AND payload_sha256=? AND result_hash=?
                AND attempt_token=?::uuid AND deletion_generation=? AND disposition='PERSISTED')""",
                Boolean::class.java, run.id.value, descriptor.jobId, descriptor.payloadId, descriptor.payloadSha256,
                descriptor.resultHash, descriptor.attemptToken, descriptor.deletionGeneration) == true
        }
        if (session != listOf("VALIDATING", run.input.deletionGeneration.toString(), "false", run.id.value, "")) return false
        val current = jdbc.query("""SELECT r.status,r.attempt_token::text,r.compute_job_id,r.input_fingerprint,
            r.deletion_generation,d.status,d.lease_owner::text,d.lease_expires_at,s.state,r.version,
            r.result_schema_version,r.result_hash_specification_version
            FROM trading_normalization_runs r JOIN trading_normalization_dispatches d ON d.run_id=r.id
            JOIN trading_normalization_result_staging s ON s.run_id=r.id
            WHERE r.id=?::uuid AND d.id=?::uuid AND r.owner_member_id=? AND r.trading_book_id=?
            AND r.import_session_id=? FOR UPDATE OF r,d,s""", { rs, _ ->
            listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getLong(5).toString(), rs.getString(6), rs.getString(7) ?: "",
                rs.getTimestamp(8)?.toInstant()?.toString() ?: "", rs.getString(9), rs.getLong(10).toString(),
                rs.getString(11), rs.getString(12)) }, run.id.value, dispatch.id, candidate.owner.value,
            candidate.bookId.value, candidate.importSessionId.value).firstOrNull() ?: return false
        // Re-read wall time after all locks: a request that waited past its lease cannot publish.
        val now = clock.instant()
        if (now.isBefore(lease.observedAt) || current[0] != "RESULT_READY" || current[1] != descriptor.attemptToken ||
            current[2] != descriptor.jobId || current[3] != descriptor.inputSetHash ||
            current[4] != descriptor.deletionGeneration.toString() || current[5] != "RESULT_READY" ||
            current[6] != lease.owner || current[7].isBlank() || !Instant.parse(current[7]).isAfter(now) ||
            current[8] !in setOf("RECEIVING", "VALIDATED") || current[9] != run.version.toString() ||
            current[10] != descriptor.resultSchemaVersion || current[11] != descriptor.resultHashSpecificationVersion) return false
        val staged = staging.loadStageDescriptor(run, dispatch) ?: return false
        if (!same(descriptor, staged) || !metadataMatchesStage(candidate, run.id.value)) return false
        val actualCounts = staging.stagedCounts(run.id.value)
        val sectionCounts = mapOf("positionAllocationCount" to "POSITION_ALLOCATIONS",
            "excludedReviewUnitCount" to "EXCLUDED_REVIEW_UNITS", "validationIssueCount" to "VALIDATION_ISSUES")
        if (candidate.result.counts.any { (key, count) -> count != (actualCounts[
                LedgerRevisionResult.COUNT_SECTIONS[key] ?: sectionCounts.getValue(key)] ?: 0L) })
            throw NormalizationResultStoreFailure("RESULT_COUNT_MISMATCH")
        if (staging.hasInvalidEvidence(run) || (run.resultVersionPin == NormalizationResultVersionPin.V2 &&
                staging.hasInvalidSupplement(run.id.value))) throw NormalizationResultStoreFailure("EVIDENCE_REFERENCE_INVALID")
        staging.markValidated(run.id.value, now)
        val number = jdbc.queryForObject("SELECT COALESCE(MAX(revision_number),0)+1 FROM trading_ledger_revisions WHERE trading_book_id=?",
            Long::class.java, candidate.bookId.value) ?: 1L
        val revision = candidate.publish(number, now)
        val revisionId = revision.id.value.toString()
        val identity = revision.inputIdentity
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
        val row = LedgerRevisionPersistenceMapper.toRecord(revision)
        jdbc.update("""INSERT INTO trading_ledger_revisions
            (id,run_id,trading_book_id,import_session_id,revision_number,input_set_hash,
            input_hash_specification_version,input_identity_json,target_version_set_json,
            result_schema_version,result_hash_specification_version,result_hash,ledger_content_hash,
            reconciliation_manifest_hash,reconciliation_manifest_json,coverage_json,capability_json,
            quality_report_json,counts_json,section_digests_json,published_at)
            VALUES (?::uuid,?::uuid,?,?,?,?,?,?::jsonb,?::jsonb,?,?,?,?,?,
            $manifestProjection,?::jsonb,?::jsonb,$qualityProjection,?::jsonb,?::jsonb,?)""",
            row.id, row.runId, row.bookId, row.importSessionId, row.revisionNumber, row.inputSetHash,
            row.inputHashSpecificationVersion, row.inputIdentityJson, row.targetVersionSetJson,
            row.resultSchemaVersion, row.resultHashSpecificationVersion, row.resultHash, row.ledgerContentHash,
            row.manifestHash, row.manifestBaseJson, row.runId, row.runId, row.runId, row.coverageJson,
            row.capabilityJson, row.qualityBaseJson, row.runId, row.countsJson, row.sectionDigestsJson,
            Timestamp.from(row.publishedAt))
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
        check(jdbc.update("""UPDATE trading_books SET latest_ledger_revision_id=?::uuid,
            updated_at=?,version=version+1 WHERE id=? AND version=? AND status='ACTIVE'""", revisionId, Timestamp.from(now),
            run.input.tradingBookId.value, book.second) == 1)
        jdbc.update("""UPDATE trading_normalization_runs SET status='PUBLISHED',
            completed_at=?,updated_at=?,version=version+1 WHERE id=?::uuid""",
            Timestamp.from(now), Timestamp.from(now), run.id.value)
        jdbc.update("""UPDATE trading_normalization_attempts SET completed_at=?,result_category='PUBLISHED'
            WHERE run_id=?::uuid AND attempt_token=?::uuid AND completed_at IS NULL""",
            Timestamp.from(now), run.id.value, descriptor.attemptToken)
        jdbc.update("""UPDATE trading_normalization_dispatches SET status='PUBLISHED',
            lease_owner=NULL,lease_expires_at=NULL,updated_at=? WHERE id=?::uuid""",
            Timestamp.from(now), dispatch.id)
        NormalizationAcknowledgementRows(jdbc).enqueue(run, descriptor, "PERSISTED", null, now)
        jdbc.update("""INSERT INTO trading_normalization_audit(run_id,event,occurred_at)
            VALUES (?::uuid,'LEDGER_PUBLISHED',?)""", run.id.value, Timestamp.from(now))
        jdbc.update("DELETE FROM trading_normalization_result_staging WHERE run_id=?::uuid", run.id.value)
        return true
    }


    private fun bound(p: LedgerPublication): Boolean {
        val r = p.revision
        val run = p.run
        val d = p.descriptor
        return p.dispatch.id == p.lease.dispatchId && p.dispatch.runId == run.id.value &&
            r.runId == run.id && r.owner.value == run.input.ownerId && r.bookId == run.input.tradingBookId &&
            r.importSessionId == run.input.importSessionId && r.inputSetHash == run.inputFingerprint &&
            d.runId == run.id.value && d.logicalJobId == p.dispatch.id && d.jobId == run.computeJobId &&
            d.attemptToken == run.attemptToken && d.deletionGeneration == run.input.deletionGeneration &&
            d.inputSetHash == r.inputSetHash && d.inputHashSpecificationVersion == r.inputIdentity.hashSpecificationVersion &&
            same(d.targetVersionSet, r.inputIdentity.targetVersionSet.toWireValue()) &&
            d.resultHash == r.result.resultHash && d.reconciliationManifestHash == r.result.reconciliationManifestHash &&
            same(d.counts, r.result.counts) && same(d.sectionDigests, r.result.sectionDigests.map { it.toWireValue() }) &&
            run.inputIdentity?.let { same(it.toWireValue(), r.inputIdentity.toWireValue()) } == true
    }

    private fun same(a: Any?, b: Any?): Boolean =
        mapper.readTree(mapper.writeValueAsString(a)) == mapper.readTree(mapper.writeValueAsString(b))

    private fun sameContent(a: LedgerRevision, b: LedgerRevision): Boolean =
        a.runId == b.runId && a.owner == b.owner && a.bookId == b.bookId && a.importSessionId == b.importSessionId &&
        a.inputSetHash == b.inputSetHash && a.result.versionPin == b.result.versionPin &&
        a.result.resultHash == b.result.resultHash && a.result.ledgerContentHash == b.result.ledgerContentHash &&
        a.result.reconciliationManifestHash == b.result.reconciliationManifestHash && same(a.result.counts, b.result.counts) &&
        same(a.result.sectionDigests.map { it.toWireValue() }, b.result.sectionDigests.map { it.toWireValue() }) &&
        same(mapper.readValue(a.metadata.manifestBaseJson, Map::class.java).filterKeys { it !in MANIFEST_ARRAYS },
            mapper.readValue(b.metadata.manifestBaseJson, Map::class.java).filterKeys { it !in MANIFEST_ARRAYS }) &&
        same(mapper.readValue(a.metadata.qualityBaseJson, Map::class.java).filterKeys { it != "validationIssues" },
            mapper.readValue(b.metadata.qualityBaseJson, Map::class.java).filterKeys { it != "validationIssues" }) &&
        mapper.readTree(a.metadata.coverageJson) == mapper.readTree(b.metadata.coverageJson) &&
        mapper.readTree(a.metadata.capabilityJson) == mapper.readTree(b.metadata.capabilityJson)

    private fun metadataMatchesStage(revision: LedgerRevision, runId: String): Boolean {
        val metadata = revision.metadata
        fun matches(section: String, json: String, strip: String): Boolean = jdbc.queryForObject(
            """SELECT count(*)=1 AND bool_and((item_json $strip)=(?::jsonb $strip))
                FROM trading_normalization_staged_items WHERE run_id=?::uuid AND section_type=?""",
            Boolean::class.java, json, runId, section) == true
        if (!matches("RECONCILIATION_MANIFEST", metadata.manifestBaseJson,
                "- 'reviewUnits' - 'reconciliationMatches' - 'reconciliationIssues' - 'artifactCoverages' - 'capability'") ||
            !matches("QUALITY_REPORT", metadata.qualityBaseJson, "- 'validationIssues'")) return false
        fun collection(section: String, json: String): Boolean = jdbc.queryForObject("""SELECT
            COALESCE(jsonb_agg(item_json ORDER BY item_sort_key COLLATE "C"),'[]'::jsonb)=?::jsonb
            FROM trading_normalization_staged_items WHERE run_id=?::uuid AND section_type=?""",
            Boolean::class.java, json, runId, section) == true
        return collection("ARTIFACT_COVERAGES", metadata.coverageJson) && collection("CAPABILITIES", metadata.capabilityJson)
    }
    companion object { private val MANIFEST_ARRAYS = setOf("reviewUnits", "reconciliationMatches", "reconciliationIssues") }
}
