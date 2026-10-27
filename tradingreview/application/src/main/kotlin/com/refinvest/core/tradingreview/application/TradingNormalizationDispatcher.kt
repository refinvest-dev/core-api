package com.refinvest.core.tradingreview.application

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.*
import com.refinvest.core.tradingreview.port.*
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import tools.jackson.databind.ObjectMapper
import kotlin.math.min
import kotlin.random.Random

@Service
class DefaultTradingRetryJitter : TradingRetryJitter {
    override fun factor(): Double = Random.nextDouble()
}

@Service
class TradingNormalizationDispatcher(
    private val runs: TradingNormalizationStore,
    private val sessions: TradingImportSessionStore,
    private val books: TradingBookStore,
    private val artifacts: TradingSourceArtifactStore,
    private val grants: ArtifactAccessGrantIssuer,
    private val compute: TradingNormalizationComputeClient,
    private val jitter: TradingRetryJitter,
    private val transactions: TransactionTemplate,
    private val clock: Clock,
    private val mapper: ObjectMapper,
) {
    fun dispatchOne(): Boolean {
        val now = clock.instant()
        val workerId = UUID.randomUUID().toString()
        val dispatch = transactions.execute {
            runs.claimNext(now, Duration.ofSeconds(45), workerId)
        } ?: return false
        val run = runs.findRun(dispatch.runId) ?: return true
        val session = sessions.find(run.input.importSessionId, MemberId(run.input.ownerId))
        if (session == null || !isCurrent(run, session)) {
            transactions.execute { runs.markObsolete(dispatch.id, workerId, clock.instant()) }
            return true
        }
        val submission = try {
            if (run.inputIdentity == null) throw TradingFailure("INTERNAL_REQUEST_CONTRACT_INVALID", 422)
            val current = artifacts.list(run.input.importSessionId)
            for (pinned in run.input.artifacts) {
                val live = current.singleOrNull { it.id == pinned.artifactId && it.role == pinned.role }
                    ?: throw TradingFailure("SOURCE_ARTIFACT_EXPIRED", 410)
                if (live.sha256 != pinned.sha256 || live.byteSize != pinned.contentLength)
                    throw TradingFailure("ARTIFACT_IDENTITY_CONFLICT", 409)
                if (!live.rawReparseAvailable(clock.instant()))
                    throw TradingFailure("SOURCE_ARTIFACT_EXPIRED", 410)
            }
            val issued = run.input.artifacts.map { pinned -> grants.issueReadGrant(pinned,
                dispatch.id, run.input.importSessionId, run.attemptToken, run.input.deletionGeneration) }
            compute.submit(run, dispatch, issued)
        } catch (error: TradingFailure) {
            when (error.code) {
                "SOURCE_ARTIFACT_EXPIRED", "ARTIFACT_IDENTITY_CONFLICT",
                "INTERNAL_REQUEST_CONTRACT_INVALID" -> NormalizationSubmission.Rejected(error.code)
                else -> NormalizationSubmission.Retry("TEMPORARY_DEPENDENCY_FAILURE")
            }
        } catch (_: Exception) {
            NormalizationSubmission.Retry("TEMPORARY_DEPENDENCY_FAILURE")
        }
        transactions.execute {
            val locked = sessions.find(run.input.importSessionId, MemberId(run.input.ownerId), forUpdate = true)
            val at = clock.instant()
            if (locked == null || !isCurrent(run, locked)) {
                runs.markObsolete(dispatch.id, workerId, at)
                return@execute
            }
            when (submission) {
                is NormalizationSubmission.Accepted ->
                    runs.markSubmitted(dispatch.id, workerId, run.id, run.input.deletionGeneration,
                        run.attemptToken, submission.jobId, at)
                is NormalizationSubmission.Retry -> {
                    val policy = policy(run)
                    if (dispatch.attemptCount >= policy.maxAttempts ||
                        Duration.between(run.createdAt, at) >= policy.maxElapsedTime)
                        fail(run, dispatch, locked, workerId, "TEMPORARY_DEPENDENCY_FAILURE", at)
                    else {
                        val delay = delay(policy, dispatch.attemptCount, submission.retryAfter)
                        if (at.plus(delay).isAfter(run.createdAt.plus(policy.maxElapsedTime)))
                            fail(run, dispatch, locked, workerId, "TEMPORARY_DEPENDENCY_FAILURE", at)
                        else if (runs.scheduleRetry(dispatch.id, workerId, submission.code, at.plus(delay), at))
                            runs.audit(run.id, "DISPATCH_RETRY", submission.code, at)
                    }
                }
                is NormalizationSubmission.Rejected -> fail(run, dispatch, locked, workerId,
                    submission.code.takeIf { it in SAFE_CODES } ?: "INTERNAL_ERROR", at)
            }
        }
        return true
    }

    fun pollOne(): Boolean {
        val now = clock.instant()
        val workerId = UUID.randomUUID().toString()
        val dispatch = transactions.execute {
            runs.claimSubmitted(now, Duration.ofSeconds(45), workerId)
        } ?: return false
        val run = runs.findRun(dispatch.runId) ?: return true
        val session = sessions.find(run.input.importSessionId, MemberId(run.input.ownerId))
        if (session == null || !isCurrent(run, session)) {
            transactions.execute { runs.markObsolete(dispatch.id, workerId, clock.instant()) }
            return true
        }
        val lookup = compute.status(run, dispatch)
        when (lookup) {
            is NormalizationStatusLookup.Retry -> transactions.execute {
                val locked = sessions.find(run.input.importSessionId, MemberId(run.input.ownerId), true)
                val at = clock.instant()
                if (locked == null || !isCurrent(run, locked)) runs.markObsolete(dispatch.id, workerId, at)
                else {
                    val pinned = policy(run)
                    val failureCount = dispatch.pollFailureCount + 1
                    val next = at.plus(delay(pinned, failureCount, null))
                    if (failureCount >= pinned.maxAttempts ||
                        !next.isBefore(run.createdAt.plus(pinned.maxElapsedTime)))
                        fail(run, dispatch, locked, workerId, lookup.code, at)
                    else runs.schedulePollFailure(dispatch.id, workerId, lookup.code, next, at)
                }
            }
            is NormalizationStatusLookup.Rejected -> transactions.execute {
                val locked = sessions.find(run.input.importSessionId, MemberId(run.input.ownerId), true)
                if (locked != null && isCurrent(run, locked))
                    fail(run, dispatch, locked, workerId,
                        lookup.code.takeIf { it in SAFE_CODES } ?: "INTERNAL_ERROR", clock.instant())
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
                if (locked != null && isCurrent(run, locked))
                    fail(run, dispatch, locked, workerId, "INTERNAL_ERROR", clock.instant())
                else runs.markObsolete(dispatch.id, workerId, clock.instant())
            }
            return
        }
        if (dispatch.resultVersionPin != run.resultVersionPin ||
            status.resultVersionPin != run.resultVersionPin) {
            transactions.execute {
                val locked = sessions.find(run.input.importSessionId, MemberId(run.input.ownerId), true)
                if (locked != null && isCurrent(run, locked))
                    fail(run, dispatch, locked, workerId, "RESULT_VERSION_PIN_MISMATCH", clock.instant())
                else runs.markObsolete(dispatch.id, workerId, clock.instant())
            }
            return
        }
        if (status.state in setOf("COMPLETED", "FAILED", "CANCELLED") &&
            status.attemptToken != run.attemptToken) {
            transactions.execute {
                val locked = sessions.find(run.input.importSessionId, MemberId(run.input.ownerId), true)
                val at = clock.instant()
                if (locked == null || !isCurrent(run, locked)) runs.markObsolete(dispatch.id, workerId, at)
                else if (Duration.between(run.createdAt, at) >= policy(run).maxElapsedTime)
                    fail(run, dispatch, locked, workerId, "TEMPORARY_DEPENDENCY_FAILURE", at)
                else runs.schedulePoll(dispatch.id, workerId, at.plusSeconds(5), at)
            }
            return
        }
        when (status.state) {
            "COMPLETED" -> transactions.execute {
                val locked = sessions.find(run.input.importSessionId, MemberId(run.input.ownerId), true)
                if (locked != null && isCurrent(run, locked)) {
                    if (runs.markResultReady(dispatch.id, workerId, clock.instant()))
                        runs.audit(run.id, "RESULT_READY", null, clock.instant())
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
                    if (locked != null && isCurrent(run, locked))
                        fail(run, dispatch, locked, workerId, "INTERNAL_ERROR", clock.instant())
                    else runs.markObsolete(dispatch.id, workerId, clock.instant())
                }
            }
            else -> transactions.execute {
                val locked = sessions.find(run.input.importSessionId, MemberId(run.input.ownerId), true)
                if (locked != null && isCurrent(run, locked))
                    fail(run, dispatch, locked, workerId, "INTERNAL_ERROR", clock.instant())
                else runs.markObsolete(dispatch.id, workerId, clock.instant())
            }
        }
    }

    private fun refreshGrants(run: NormalizationRun, dispatch: NormalizationDispatch,
                              workerId: String, status: NormalizationJobStatus) {
        val now = clock.instant()
        val locked = transactions.execute {
            val session = sessions.find(run.input.importSessionId, MemberId(run.input.ownerId), true)
            if (session == null || !isCurrent(run, session)) {
                runs.markObsolete(dispatch.id, workerId, clock.instant())
                return@execute false
            }
            if (status.grantRefreshDeadlineAt?.isBefore(now) == true) {
                fail(run, dispatch, session, workerId, "TEMPORARY_DEPENDENCY_FAILURE", now)
                return@execute false
            }
            if (status.grantAction == "START_RETRY_ATTEMPT" && run.attemptToken == status.attemptToken) {
                if (run.currentAttempt >= policy(run).maxAttempts) {
                    fail(run, dispatch, session, workerId, "TEMPORARY_DEPENDENCY_FAILURE", now)
                    return@execute false
                }
                runs.advanceAttempt(run.id, dispatch.id, workerId, status.attemptToken,
                    UUID.randomUUID().toString(), now)
            } else true
        } ?: false
        if (!locked) return
        val currentRun = runs.findRun(run.id) ?: return
        val update = try {
            val issued = currentRun.input.artifacts.map { grants.issueReadGrant(it, dispatch.id,
                currentRun.input.importSessionId, currentRun.attemptToken,
                currentRun.input.deletionGeneration) }
            compute.updateGrants(currentRun, dispatch, status, currentRun.attemptToken,
                issued, UUID.randomUUID().toString())
        } catch (error: TradingFailure) {
            if (error.code == "SOURCE_ARTIFACT_EXPIRED") NormalizationGrantUpdate.Rejected(error.code)
            else NormalizationGrantUpdate.Retry
        } catch (_: Exception) { NormalizationGrantUpdate.Retry }
        transactions.execute {
            val session = sessions.find(run.input.importSessionId, MemberId(run.input.ownerId), true)
            val at = clock.instant()
            if (session == null || !isCurrent(run, session)) runs.markObsolete(dispatch.id, workerId, at)
            else when (update) {
                is NormalizationGrantUpdate.Rejected -> fail(run, dispatch, session, workerId,
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
            if (session == null || !isCurrent(run, session)) {
                runs.markObsolete(dispatch.id, workerId, now)
                return@execute
            }
            if (code !in RETRYABLE_JOB_CODES && code != "SOURCE_ARTIFACT_EXPIRED") {
                if (code in SOURCE_REJECTION_CODES) {
                    if (!runs.markTerminal(dispatch.id, workerId, code, now)) return@execute
                    val rejected = session.rejectValidation(run.id, run.input.deletionGeneration,
                        TradingImportFailure(code, false, "NONE", now), now) ?: return@execute
                    check(sessions.update(rejected))
                    runs.audit(run.id, "SOURCE_REJECTED", code, now)
                } else fail(run, dispatch, session, workerId,
                    code.takeIf { it in NON_RETRYABLE_COMPUTE_CODES } ?: "INTERNAL_ERROR", now)
                return@execute
            }
            val policy = policy(run)
            if (code in RETRYABLE_JOB_CODES && run.currentAttempt < policy.maxAttempts &&
                Duration.between(run.createdAt, now) < policy.maxElapsedTime) {
                val nextAt = now.plus(delay(policy, run.currentAttempt, null))
                if (nextAt.isBefore(run.createdAt.plus(policy.maxElapsedTime))) {
                    val nextToken = UUID.randomUUID().toString()
                    val next = NormalizationDispatch(UUID.randomUUID().toString(), run.id,
                        NormalizationInputFingerprint.dispatchKey(run.id, run.logicalKey, nextToken,
                            run.input.versionSetJson, run.input.deletionGeneration, mapper),
                        NormalizationDispatchStatus.PENDING, 0,
                        nextAt, null, null, code, now, now,
                        resultVersionPin = run.resultVersionPin)
                    runs.restartAfterJobFailure(run.id, dispatch.id, workerId, next,
                        nextToken, code, now)
                    return@execute
                }
            }
            fail(run, dispatch, session, workerId,
                if (code == "SOURCE_ARTIFACT_EXPIRED") code else "TEMPORARY_DEPENDENCY_FAILURE", now)
        }
    }

    private fun fail(run: NormalizationRun, dispatch: NormalizationDispatch, session: TradingImportSession,
                     workerId: String, code: String, now: Instant) {
        val rawAvailable = artifacts.list(run.input.importSessionId).let { live ->
            run.input.artifacts.all { pinned -> live.any {
                it.id == pinned.artifactId && it.sha256 == pinned.sha256 &&
                    it.byteSize == pinned.contentLength && it.rawReparseAvailable(now)
            } }
        }
        val safeCode = if (!rawAvailable && code == "TEMPORARY_DEPENDENCY_FAILURE")
            "SOURCE_ARTIFACT_EXPIRED" else code
        if (!runs.markTerminal(dispatch.id, workerId, safeCode, now)) return
        val retryable = safeCode == "TEMPORARY_DEPENDENCY_FAILURE" && rawAvailable
        val action = when (safeCode) {
            "SOURCE_ARTIFACT_EXPIRED" -> "REUPLOAD_REQUIRED"
            "TEMPORARY_DEPENDENCY_FAILURE" -> "RETRY"
            else -> "WAIT_FOR_RECOVERY"
        }
        val failure = TradingImportFailure(safeCode, retryable, action, now)
        val failed = session.failValidation(run.id, run.input.deletionGeneration, failure, now)
            ?: return
        if (!sessions.update(failed)) throw IllegalStateException("Session transition raced")
        runs.audit(run.id, "PROCESSING_FAILED", safeCode, now)
    }

    private fun isCurrent(run: NormalizationRun, session: TradingImportSession): Boolean =
        !session.deletionPending && session.status == TradingImportStatus.VALIDATING &&
            session.activeNormalizationRunId == run.id &&
            session.deletionGeneration == run.input.deletionGeneration &&
            books.find(run.input.tradingBookId, MemberId(run.input.ownerId))?.status == TradingLifecycleStatus.ACTIVE

    private fun delay(policy: TradingNormalizationRetryPolicy, count: Int, retryAfter: Duration?): Duration {
        val scaled = policy.initialDelay.toMillis().toDouble() * (1L shl min(count - 1, 20))
        val capped = min(scaled, policy.maxDelay.toMillis().toDouble())
        val withJitter = if (policy.jitter == "FULL_JITTER") capped * jitter.factor() else capped
        return Duration.ofMillis(maxOf(withJitter.toLong(), retryAfter?.toMillis() ?: 0L, 0L))
    }

    private fun policy(run: NormalizationRun): TradingNormalizationRetryPolicy {
        val pinned = mapper.readTree(run.input.retryPolicyJson)
        return TradingNormalizationRetryPolicy(
            pinned.path("policyVersion").asText(), pinned.path("maxAttempts").asInt(),
            Duration.parse(pinned.path("maxElapsedTime").asText()),
            Duration.parse(pinned.path("initialDelay").asText()),
            Duration.parse(pinned.path("maxDelay").asText()), pinned.path("jitter").asText())
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
        val SAFE_CODES = setOf("SOURCE_ARTIFACT_EXPIRED", "ARTIFACT_IDENTITY_CONFLICT",
            "RESULT_SCHEMA_UNSUPPORTED", "RESULT_VERSION_PIN_MISMATCH", "INTERNAL_ERROR")
    }
}
