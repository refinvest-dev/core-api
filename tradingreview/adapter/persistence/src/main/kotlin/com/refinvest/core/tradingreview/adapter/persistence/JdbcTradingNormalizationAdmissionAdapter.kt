package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.tradingreview.domain.service.NormalizationInputCanonicalizer
import com.refinvest.core.tradingreview.domain.importsession.*
import com.refinvest.core.tradingreview.port.importsession.TradingImportSessionStore
import com.refinvest.core.tradingreview.port.TradingNormalizationTransitionGate
import com.refinvest.core.tradingreview.port.normalization.*
import java.sql.Timestamp
import java.time.Clock
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper

@Repository
class JdbcTradingNormalizationAdmissionAdapter(
    private val jdbc: JdbcTemplate,
    private val sessions: TradingImportSessionStore,
    private val clock: Clock,
    private val transition: TradingNormalizationTransitionGate,
) : TradingNormalizationAdmission {
    private val json = JsonMapper.builder().build()

    @Transactional
    override fun admit(admission: NormalizationAdmission): Boolean {
        check(jdbc.execute(ConnectionCallback { !it.autoCommit }) == true) { "Admission requires an enclosing transaction" }
        transition.requireAdmission()
        val expected = admission.expected
        val proposed = admission.admitted
        val run = requireNotNull(proposed.normalizationRun) { "Admission requires a current Run" }
        val dispatch = admission.dispatch
        val record = NormalizationRunPersistenceMapper.toRecord(run)
        require(dispatch.runId == run.id.value && dispatch.resultVersionPin == run.resultVersionPin &&
            dispatch.status == com.refinvest.core.tradingreview.domain.valueobject.NormalizationDispatchStatus.PENDING &&
            dispatch.attemptCount == 0 && dispatch.leaseOwner == null && dispatch.leaseExpiresAt == null &&
            dispatch.pollFailureCount == 0 && dispatch.lastFailureCode == null && dispatch.createdAt == run.createdAt &&
            dispatch.updatedAt == run.createdAt && dispatch.nextAttemptAt == run.createdAt &&
            dispatch.computeKey == NormalizationInputCanonicalizer.dispatchKey(run.id.value, run.logicalKey,
                run.attemptToken, requireNotNull(run.inputIdentity).targetVersionSet.versionSetHash, run.input.deletionGeneration))
        val accountId = jdbc.query("SELECT account_id FROM trading_books WHERE id=? AND owner_member_id=?",
            { rs, _ -> rs.getLong(1) }, expected.bookId.value, expected.owner.value).firstOrNull() ?: return false
        val account = jdbc.query("SELECT status FROM trading_accounts WHERE id=? AND owner_member_id=? FOR UPDATE",
            { rs, _ -> rs.getString(1) }, accountId, expected.owner.value).firstOrNull()
        if (account != "ACTIVE") return false
        val book = jdbc.query("""SELECT venue,product_family,position_mode,settlement_asset FROM trading_books
            WHERE id=? AND owner_member_id=? AND status='ACTIVE' FOR UPDATE""",
            { rs, _ -> listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)) },
            expected.bookId.value, expected.owner.value).firstOrNull() ?: return false
        if (book != listOf(run.input.venue, run.input.productFamily, run.input.positionMode, run.input.settlementAsset)) return false
        val current = sessions.find(expected.id, expected.owner, true) ?: return false
        val currentRecord = TradingImportSessionPersistenceMapper.toRecord(current)
        val expectedRecord = TradingImportSessionPersistenceMapper.toRecord(expected)
        if (currentRecord.copy(artifacts = emptyList(), normalizationRun = null) !=
            expectedRecord.copy(artifacts = emptyList(), normalizationRun = null)) return false
        if (current.artifacts.map { it.id to it.version }.toSet() != expected.artifacts.map { it.id to it.version }.toSet()) return false
        if (!sameArtifacts(current, proposed)) return false
        val observedAt = clock.instant()
        if (observedAt.isBefore(run.createdAt) || run.createdAt.isBefore(current.updatedAt) ||
            current.artifacts.any { !it.rawReparseAvailable(observedAt) }) return false
        val previousVersion = current.activeNormalizationRunId?.let { id -> jdbc.query(
            "SELECT version FROM trading_normalization_runs WHERE id=?::uuid FOR UPDATE",
            { rs, _ -> rs.getLong(1) }, id.value).firstOrNull() }
        if (previousVersion != expected.normalizationRun?.version) return false
        val derived = try {
            current.admitNormalization(run.id, run.input, run.logicalKey, run.attemptToken,
                requireNotNull(run.inputIdentity), json.readValue(current.versionSetJson, Map::class.java),
                requireNotNull(run.resultDeliveryPolicy), run.resultVersionPin, run.createdAt)
        } catch (_: IllegalArgumentException) { return false }
        val derivedRecord = TradingImportSessionPersistenceMapper.toRecord(derived)
        val proposedRecord = TradingImportSessionPersistenceMapper.toRecord(proposed)
        if (derivedRecord.copy(artifacts = emptyList(), normalizationRun = null) !=
            proposedRecord.copy(artifacts = emptyList(), normalizationRun = null) ||
            NormalizationRunPersistenceMapper.toRecord(requireNotNull(derived.normalizationRun)) != record) return false
        if (jdbc.queryForObject("""SELECT EXISTS(SELECT 1 FROM trading_normalization_runs
            WHERE import_session_id=? AND status IN ('DISPATCH_PENDING','SUBMITTED','RUNNING','RESULT_READY','PUBLISHING','RETRY_WAIT'))""",
            Boolean::class.java, current.id.value) == true) return false
        NormalizationAdmissionRows(jdbc).insert(run, dispatch)
        check(jdbc.update("""UPDATE trading_import_sessions SET status='VALIDATING',
            active_normalization_run_id=?::uuid, validation_started_at=?, updated_at=?,
            failure_code=NULL,failure_retryable=NULL,failure_user_action=NULL,failure_occurred_at=NULL,version=version+1
            WHERE id=? AND owner_member_id=? AND version=? AND deletion_generation=? AND NOT deletion_pending""",
            run.id.value, Timestamp.from(run.createdAt), Timestamp.from(run.createdAt), current.id.value,
            current.owner.value, current.version, current.deletionGeneration) == 1)
        jdbc.update("INSERT INTO trading_normalization_audit(run_id,event,safe_failure_code,occurred_at) VALUES (?::uuid,?,NULL,?)",
            run.id.value, if (run.retryOfRunId == null) "VALIDATION_ADMITTED" else "EXPLICIT_RETRY", Timestamp.from(run.createdAt))
        return true
    }
    private fun sameArtifacts(left: com.refinvest.core.tradingreview.domain.TradingImportSession,
                              right: com.refinvest.core.tradingreview.domain.TradingImportSession): Boolean {
        if (left.artifacts.size != right.artifacts.size) return false
        return left.artifacts.all { artifact ->
            val other = right.artifacts.singleOrNull { it.id == artifact.id } ?: return@all false
            val a = TradingImportSessionPersistenceMapper.toRecord(artifact)
            val b = TradingImportSessionPersistenceMapper.toRecord(other)
            a.copy(wrappedDek = null) == b.copy(wrappedDek = null) && java.util.Arrays.equals(a.wrappedDek, b.wrappedDek)
        }
    }

}
