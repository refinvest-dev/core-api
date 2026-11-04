package com.refinvest.core.tradingreview.application.normalization.dispatch

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
import com.refinvest.core.tradingreview.port.common.failure.TradingFailure
import com.refinvest.core.tradingreview.port.common.failure.TradingFailureKind
import com.refinvest.core.tradingreview.port.inbound.normalization.dispatch.DispatchTradingNormalizationUseCase
import com.refinvest.core.tradingreview.port.outbound.persistence.importsession.TradingSourceArtifactReader
import com.refinvest.core.tradingreview.port.outbound.compute.normalization.NormalizationSubmission
import com.refinvest.core.tradingreview.port.outbound.persistence.normalization.NormalizationDispatchLease
import com.refinvest.core.tradingreview.port.outbound.persistence.normalization.NormalizationSubmissionReceipt
import com.refinvest.core.tradingreview.port.outbound.persistence.normalization.TradingNormalizationTransitionGate

@Service
class DispatchTradingNormalizationService(
    private val runs: TradingNormalizationRuntime,
    private val sessions: TradingImportSessionStore,
    private val artifacts: TradingSourceArtifactReader,
    private val grants: ArtifactAccessGrantIssuer,
    private val compute: TradingNormalizationComputeClient,
    private val retries: NormalizationRetrySchedule,
    private val lifecycle: NormalizationWorkerLifecycle,
    private val transactions: TransactionTemplate,
    private val clock: Clock,
    private val transition: TradingNormalizationTransitionGate,
) : DispatchTradingNormalizationUseCase {
    override fun dispatchOne(): Boolean = transition.withCreatePermit { dispatchPermitted() } ?: false

    private fun dispatchPermitted(): Boolean {
        val now = clock.instant()
        // Claim ownership is a worker nonce, independent of durable execution identity.
        val workerId = UUID.randomUUID().toString()
        val dispatch = transactions.execute {
            runs.claimNext(now, Duration.ofSeconds(45), workerId)
        } ?: return false
        val run = runs.findRun(NormalizationRunId(dispatch.runId)) ?: return true
        val session = sessions.find(run.input.importSessionId, MemberId(run.input.ownerId))
        if (session == null || !lifecycle.isCurrent(run, session)) {
            transactions.execute { runs.markObsolete(dispatch.id, workerId, clock.instant()) }
            return true
        }
        val submission = try {
            if (run.inputIdentity == null) throw TradingFailure("INTERNAL_REQUEST_CONTRACT_INVALID", TradingFailureKind.INPUT_REJECTED)
            val current = artifacts.list(run.input.importSessionId)
            for (pinned in run.input.artifacts) {
                val live = current.singleOrNull { it.id == pinned.artifactId && it.role == pinned.role }
                    ?: throw TradingFailure("SOURCE_ARTIFACT_EXPIRED", TradingFailureKind.NO_LONGER_AVAILABLE)
                if (live.sha256 != pinned.sha256 || live.byteSize != pinned.contentLength)
                    throw TradingFailure("ARTIFACT_IDENTITY_CONFLICT", TradingFailureKind.CONFLICT)
                if (!live.rawReparseAvailable(clock.instant()))
                    throw TradingFailure("SOURCE_ARTIFACT_EXPIRED", TradingFailureKind.NO_LONGER_AVAILABLE)
            }
            val issued = run.input.artifacts.map { pinned -> grants.issueReadGrant(pinned,
                dispatch.id, run.input.importSessionId, run.attemptToken, run.input.deletionGeneration) }
            compute.submit(run, dispatch, issued)
        } catch (error: TradingFailure) {
            when (error.code) {
                "SOURCE_ARTIFACT_EXPIRED", "ARTIFACT_IDENTITY_CONFLICT",
                "INTERNAL_REQUEST_CONTRACT_INVALID" -> NormalizationSubmission.Rejected(error.code)
                else -> NormalizationSubmission.Retry("TEMPORARY_DEPENDENCY_FAILURE", outcomeUnknown = true)
            }
        } catch (_: Exception) {
            NormalizationSubmission.Retry("TEMPORARY_DEPENDENCY_FAILURE", outcomeUnknown = true)
        }
        transactions.execute {
            val locked = sessions.find(run.input.importSessionId, MemberId(run.input.ownerId), forUpdate = true)
            val at = clock.instant()
            if (locked == null || !lifecycle.isCurrent(run, locked)) {
                runs.markObsolete(dispatch.id, workerId, at)
                return@execute
            }
            when (submission) {
                is NormalizationSubmission.Accepted ->
                    runs.markSubmitted(NormalizationSubmissionReceipt(NormalizationRunId(run.id.value), NormalizationDispatchLease(dispatch.id, workerId, at), run.input.deletionGeneration, run.attemptToken, submission.jobId))
                is NormalizationSubmission.Retry -> {
                    if (submission.outcomeUnknown) {
                        runs.holdUnknownCreate(dispatch.id, workerId, at)
                        runs.audit(run.id.value, "CREATE_OUTCOME_UNKNOWN", submission.code, at)
                        return@execute
                    }
                    val policy = retries.policy(run)
                    if (dispatch.attemptCount >= policy.maxAttempts ||
                        Duration.between(run.createdAt, at) >= policy.maxElapsedTime)
                        lifecycle.fail(run, dispatch, locked, workerId, "TEMPORARY_DEPENDENCY_FAILURE", at)
                    else {
                        val delay = retries.delay(policy, dispatch.attemptCount, submission.retryAfter)
                        if (at.plus(delay).isAfter(run.createdAt.plus(policy.maxElapsedTime)))
                            lifecycle.fail(run, dispatch, locked, workerId, "TEMPORARY_DEPENDENCY_FAILURE", at)
                        else if (runs.scheduleRetry(dispatch.id, workerId, submission.code, at.plus(delay), at))
                            runs.audit(run.id.value, "DISPATCH_RETRY", submission.code, at)
                    }
                }
                is NormalizationSubmission.Rejected -> lifecycle.fail(run, dispatch, locked, workerId,
                    lifecycle.safeCode(submission.code), at)
            }
        }
        return true
    }

}
