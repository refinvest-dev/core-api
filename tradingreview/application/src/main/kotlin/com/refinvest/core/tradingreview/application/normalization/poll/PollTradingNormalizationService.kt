package com.refinvest.core.tradingreview.application.normalization.poll

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.valueobject.NormalizationRunId
import com.refinvest.core.tradingreview.port.outbound.artifactgrant.ArtifactAccessGrantIssuer
import com.refinvest.core.tradingreview.port.outbound.compute.normalization.TradingNormalizationComputeClient
import com.refinvest.core.tradingreview.port.outbound.persistence.importsession.TradingImportSessionStore
import com.refinvest.core.tradingreview.port.outbound.persistence.normalization.TradingNormalizationRuntime
import com.refinvest.core.tradingreview.application.normalization.NormalizationRetrySchedule
import com.refinvest.core.tradingreview.application.normalization.NormalizationWorkerLifecycle
import java.time.Clock
import java.time.Duration
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import com.refinvest.core.tradingreview.application.NormalizationInputFingerprint
import com.refinvest.core.tradingreview.domain.importsession.NormalizationRun
import com.refinvest.core.tradingreview.domain.importsession.NormalizationDispatch
import com.refinvest.core.tradingreview.domain.valueobject.NormalizationDispatchStatus
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportFailure
import com.refinvest.core.tradingreview.port.common.failure.TradingFailure
import com.refinvest.core.tradingreview.port.inbound.normalization.poll.PollTradingNormalizationUseCase
import com.refinvest.core.tradingreview.port.outbound.compute.normalization.NormalizationGrantUpdate
import com.refinvest.core.tradingreview.port.outbound.compute.normalization.NormalizationJobStatus
import com.refinvest.core.tradingreview.port.outbound.compute.normalization.NormalizationStatusLookup
import com.refinvest.core.tradingreview.port.outbound.identity.TradingNormalizationIdGenerator
import com.refinvest.core.tradingreview.port.outbound.persistence.normalization.NormalizationDispatchLease
import com.refinvest.core.tradingreview.port.outbound.persistence.normalization.NormalizationGrantAttemptAdvance
import com.refinvest.core.tradingreview.port.outbound.persistence.normalization.NormalizationJobRetry
import tools.jackson.databind.ObjectMapper

@Service
class PollTradingNormalizationService(
    private val runs: TradingNormalizationRuntime,
    private val sessions: TradingImportSessionStore,
    private val grants: ArtifactAccessGrantIssuer,
    private val compute: TradingNormalizationComputeClient,
    private val retries: NormalizationRetrySchedule,
    private val lifecycle: NormalizationWorkerLifecycle,
    private val transactions: TransactionTemplate,
    private val clock: Clock,
    private val mapper: ObjectMapper,
    private val identifiers: TradingNormalizationIdGenerator,
) : PollTradingNormalizationUseCase {
    override fun pollOne(): Boolean {
        val now = clock.instant()
        // Claim ownership is a worker nonce, independent of durable execution identity.
        val workerId = UUID.randomUUID().toString()
        val dispatch = transactions.execute {
            runs.claimSubmitted(now, Duration.ofSeconds(45), workerId)
        } ?: return false
        val run = runs.findRun(NormalizationRunId(dispatch.runId)) ?: return true
        val session = sessions.find(run.input.importSessionId, MemberId(run.input.ownerId))
        if (session == null || !lifecycle.isCurrent(run, session)) {
            transactions.execute { runs.markObsolete(dispatch.id, workerId, clock.instant()) }
            return true
        }
        val lookup = compute.status(run, dispatch)
        when (lookup) {
            is NormalizationStatusLookup.Retry -> transactions.execute {
                val locked = sessions.find(run.input.importSessionId, MemberId(run.input.ownerId), true)
                val at = clock.instant()
                if (locked == null || !lifecycle.isCurrent(run, locked)) runs.markObsolete(dispatch.id, workerId, at)
                else {
                    val pinned = retries.policy(run)
                    val failureCount = dispatch.pollFailureCount + 1
                    val next = at.plus(retries.delay(pinned, failureCount, null))
                    if (failureCount >= pinned.maxAttempts ||
                        !next.isBefore(run.createdAt.plus(pinned.maxElapsedTime)))
                        lifecycle.fail(run, dispatch, locked, workerId, lookup.code, at)
                    else runs.schedulePollFailure(dispatch.id, workerId, lookup.code, next, at)
                }
            }
            is NormalizationStatusLookup.Rejected -> transactions.execute {
                val locked = sessions.find(run.input.importSessionId, MemberId(run.input.ownerId), true)
                if (locked != null && lifecycle.isCurrent(run, locked))
                    lifecycle.fail(run, dispatch, locked, workerId,
                        lifecycle.safeCode(lookup.code), clock.instant())
                else runs.markObsolete(dispatch.id, workerId, clock.instant())
            }
            is NormalizationStatusLookup.Found -> handleStatus(run, dispatch, workerId, lookup.status)
        }
        return true
    }

    private fun handleStatus(run: NormalizationRun, dispatch: NormalizationDispatch,
                             workerId: String, status: NormalizationJobStatus) {
        val versionHash = try { mapper.readTree(run.input.versionSetJson).path("versionSetHash").asText() }
            catch (_: Exception) { "" }
        if (status.jobId != run.computeJobId || status.deletionGeneration != run.input.deletionGeneration ||
            status.expectedInputSetHash != run.inputFingerprint || status.versionSetHash != versionHash) {
            transactions.execute {
                val locked = sessions.find(run.input.importSessionId, MemberId(run.input.ownerId), true)
                if (locked != null && lifecycle.isCurrent(run, locked))
                    lifecycle.fail(run, dispatch, locked, workerId, "INTERNAL_ERROR", clock.instant())
                else runs.markObsolete(dispatch.id, workerId, clock.instant())
            }
            return
        }
        if (dispatch.resultVersionPin != run.resultVersionPin ||
            status.resultVersionPin != run.resultVersionPin) {
            transactions.execute {
                val locked = sessions.find(run.input.importSessionId, MemberId(run.input.ownerId), true)
                if (locked != null && lifecycle.isCurrent(run, locked))
                    lifecycle.fail(run, dispatch, locked, workerId, "RESULT_VERSION_PIN_MISMATCH", clock.instant())
                else runs.markObsolete(dispatch.id, workerId, clock.instant())
            }
            return
        }
        if (status.state in setOf("COMPLETED", "FAILED", "CANCELLED") &&
            status.attemptToken != run.attemptToken) {
            transactions.execute {
                val locked = sessions.find(run.input.importSessionId, MemberId(run.input.ownerId), true)
                val at = clock.instant()
                if (locked == null || !lifecycle.isCurrent(run, locked)) runs.markObsolete(dispatch.id, workerId, at)
                else if (Duration.between(run.createdAt, at) >= retries.policy(run).maxElapsedTime)
                    lifecycle.fail(run, dispatch, locked, workerId, "TEMPORARY_DEPENDENCY_FAILURE", at)
                else runs.schedulePoll(dispatch.id, workerId, at.plusSeconds(5), at)
            }
            return
        }
        when (status.state) {
            "COMPLETED" -> transactions.execute {
                val locked = sessions.find(run.input.importSessionId, MemberId(run.input.ownerId), true)
                if (locked != null && lifecycle.isCurrent(run, locked)) {
                    if (runs.markResultReady(dispatch.id, workerId, clock.instant()))
                        runs.audit(run.id.value, "RESULT_READY", null, clock.instant())
                } else runs.markObsolete(dispatch.id, workerId, clock.instant())
            }
            "FAILED", "CANCELLED" -> handleTerminalJob(run, dispatch, workerId,
                status.failureCode ?: "INTERNAL_ERROR")
            "PENDING", "RUNNING" -> {
                if (status.grantAction == "NONE") {
                    transactions.execute { runs.schedulePoll(dispatch.id, workerId,
                        clock.instant().plusSeconds(5), clock.instant()) }
                } else if (status.grantAction in setOf("REPLACE_QUEUED_GRANTS", "START_RETRY_ATTEMPT")) {
                    refreshGrants(run, dispatch, workerId, status)
                } else transactions.execute {
                    val locked = sessions.find(run.input.importSessionId, MemberId(run.input.ownerId), true)
                    if (locked != null && lifecycle.isCurrent(run, locked))
                        lifecycle.fail(run, dispatch, locked, workerId, "INTERNAL_ERROR", clock.instant())
                    else runs.markObsolete(dispatch.id, workerId, clock.instant())
                }
            }
            else -> transactions.execute {
                val locked = sessions.find(run.input.importSessionId, MemberId(run.input.ownerId), true)
                if (locked != null && lifecycle.isCurrent(run, locked))
                    lifecycle.fail(run, dispatch, locked, workerId, "INTERNAL_ERROR", clock.instant())
                else runs.markObsolete(dispatch.id, workerId, clock.instant())
            }
        }
    }

    private fun refreshGrants(run: NormalizationRun, dispatch: NormalizationDispatch,
                              workerId: String, status: NormalizationJobStatus) {
        val locked = transactions.execute {
            val session = sessions.find(run.input.importSessionId, MemberId(run.input.ownerId), true)
            val now = clock.instant()
            if (!lifecycle.hasLease(dispatch, workerId, now)) return@execute false
            if (session == null || !lifecycle.isCurrent(run, session)) {
                runs.markObsolete(dispatch.id, workerId, clock.instant())
                return@execute false
            }
            if (status.grantRefreshDeadlineAt?.let { !now.isBefore(it) } == true) {
                lifecycle.fail(run, dispatch, session, workerId, "TEMPORARY_DEPENDENCY_FAILURE", now)
                return@execute false
            }
            if (status.grantAction == "START_RETRY_ATTEMPT" && run.attemptToken == status.attemptToken) {
                if (run.currentAttempt >= retries.policy(run).maxAttempts) {
                    lifecycle.fail(run, dispatch, session, workerId, "TEMPORARY_DEPENDENCY_FAILURE", now)
                    return@execute false
                }
                runs.advanceAttempt(NormalizationGrantAttemptAdvance(NormalizationRunId(run.id.value), NormalizationDispatchLease(dispatch.id, workerId, now), status.attemptToken, identifiers.attemptToken()))
            } else true
        } ?: false
        if (!locked) return
        val currentRun = runs.findRun(NormalizationRunId(run.id.value)) ?: return
        val update = try {
            val issued = currentRun.input.artifacts.map { grants.issueReadGrant(it, dispatch.id,
                currentRun.input.importSessionId, currentRun.attemptToken,
                currentRun.input.deletionGeneration) }
            compute.updateGrants(currentRun, dispatch, status, currentRun.attemptToken,
                issued, identifiers.grantUpdateKey())
        } catch (error: TradingFailure) {
            if (error.code == "SOURCE_ARTIFACT_EXPIRED") NormalizationGrantUpdate.Rejected(error.code)
            else NormalizationGrantUpdate.Retry
        } catch (_: Exception) { NormalizationGrantUpdate.Retry }
        transactions.execute {
            val session = sessions.find(run.input.importSessionId, MemberId(run.input.ownerId), true)
            val at = clock.instant()
            if (session == null || !lifecycle.isCurrent(run, session)) runs.markObsolete(dispatch.id, workerId, at)
            else when (update) {
                is NormalizationGrantUpdate.Rejected -> lifecycle.fail(run, dispatch, session, workerId,
                    update.code.takeIf { it == "SOURCE_ARTIFACT_EXPIRED" } ?: "INTERNAL_ERROR", at)
                else -> runs.schedulePoll(dispatch.id, workerId, at.plusSeconds(5), at)
            }
        }
    }

    private fun handleTerminalJob(run: NormalizationRun, dispatch: NormalizationDispatch,
                                  workerId: String, code: String) {
        transactions.execute {
            val session = sessions.find(run.input.importSessionId, MemberId(run.input.ownerId), true)
            val now = clock.instant()
            if (session == null || !lifecycle.isCurrent(run, session)) {
                runs.markObsolete(dispatch.id, workerId, now)
                return@execute
            }
            if (code !in RETRYABLE_JOB_CODES && code != "SOURCE_ARTIFACT_EXPIRED") {
                if (code in SOURCE_REJECTION_CODES) {
                    if (!runs.markTerminal(dispatch.id, workerId, code, now)) return@execute
                    val rejected = session.rejectValidation(run.id.value, run.input.deletionGeneration,
                        TradingImportFailure(code, false, "NONE", now), now) ?: return@execute
                    check(sessions.update(rejected))
                    runs.audit(run.id.value, "SOURCE_REJECTED", code, now)
                } else lifecycle.fail(run, dispatch, session, workerId,
                    code.takeIf { it in NON_RETRYABLE_COMPUTE_CODES } ?: "INTERNAL_ERROR", now)
                return@execute
            }
            val policy = retries.policy(run)
            if (code in RETRYABLE_JOB_CODES && run.currentAttempt < policy.maxAttempts &&
                Duration.between(run.createdAt, now) < policy.maxElapsedTime) {
                val nextAt = now.plus(retries.delay(policy, run.currentAttempt, null))
                if (nextAt.isBefore(run.createdAt.plus(policy.maxElapsedTime))) {
                    val nextToken = identifiers.attemptToken()
                    val next = NormalizationDispatch(identifiers.dispatchId(), run.id.value,
                        NormalizationInputFingerprint.dispatchKey(run.id.value, run.logicalKey, nextToken,
                            run.input.versionSetJson, run.input.deletionGeneration, mapper),
                        NormalizationDispatchStatus.PENDING, 0,
                        nextAt, null, null, code, now, now,
                        resultVersionPin = run.resultVersionPin)
                    runs.restartAfterJobFailure(NormalizationJobRetry(NormalizationRunId(run.id.value), NormalizationDispatchLease(dispatch.id, workerId, now), next, nextToken, code))
                    return@execute
                }
            }
            lifecycle.fail(run, dispatch, session, workerId,
                if (code == "SOURCE_ARTIFACT_EXPIRED") code else "TEMPORARY_DEPENDENCY_FAILURE", now)
        }
    }

    private companion object {
        val RETRYABLE_JOB_CODES = setOf("ARTIFACT_REFERENCE_EXPIRED",
            "GRANT_REFRESH_DEADLINE_EXCEEDED", "TEMPORARY_DEPENDENCY_FAILURE")
        val SOURCE_REJECTION_CODES = setOf("FILE_FORMAT_INVALID", "REQUIRED_COLUMN_MISSING",
            "UNSUPPORTED_SOURCE_SCHEMA", "UNSUPPORTED_PRODUCT", "UNSUPPORTED_POSITION_MODE",
            "RECORD_CONFLICT")
        val NON_RETRYABLE_COMPUTE_CODES = setOf("INPUT_SET_HASH_MISMATCH",
            "INPUT_HASH_SPECIFICATION_UNSUPPORTED", "SOURCE_CONTENT_HASH_MISMATCH",
            "SOURCE_CONTENT_LENGTH_MISMATCH", "ARTIFACT_IDENTITY_CONFLICT",
            "ARTIFACT_ACCESS_DENIED", "VERSION_UNAVAILABLE", "VERSION_INCOMPATIBLE",
            "CANONICAL_SCHEMA_INCOMPATIBLE", "INSTRUMENT_TERMS_UNAVAILABLE",
            "RESULT_PAYLOAD_LIMIT_EXCEEDED", "RESULT_TRANSPORT_HASH_MISMATCH",
            "RESULT_COUNT_MISMATCH", "RESULT_SECTION_HASH_MISMATCH",
            "RECONCILIATION_MANIFEST_HASH_MISMATCH", "RESULT_HASH_MISMATCH",
            "RESULT_SCHEMA_UNSUPPORTED", "EVIDENCE_REFERENCE_INVALID",
            "RESULT_ITEM_CONFLICT", "RESULT_PAYLOAD_LENGTH_MISMATCH",
            "RESULT_CONTENT_ENCODING_UNSUPPORTED", "RESULT_PAYLOAD_PARSE_FAILED")
    }
}
