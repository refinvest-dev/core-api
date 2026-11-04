package com.refinvest.core.tradingreview.application.normalization

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.TradingImportSession
import com.refinvest.core.tradingreview.domain.importsession.NormalizationDispatch
import com.refinvest.core.tradingreview.domain.importsession.NormalizationRun
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportFailure
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportStatus
import com.refinvest.core.tradingreview.domain.valueobject.TradingLifecycleStatus
import com.refinvest.core.tradingreview.port.outbound.persistence.book.TradingBookStore
import com.refinvest.core.tradingreview.port.outbound.persistence.importsession.TradingImportSessionStore
import com.refinvest.core.tradingreview.port.outbound.persistence.importsession.TradingSourceArtifactReader
import com.refinvest.core.tradingreview.port.outbound.persistence.normalization.TradingNormalizationRuntime
import java.time.Instant
import org.springframework.stereotype.Component

/** Shared lifecycle checks/transitions; callers own the Session lock and short transaction. */
@Component
class NormalizationWorkerLifecycle(
    private val runs: TradingNormalizationRuntime,
    private val sessions: TradingImportSessionStore,
    private val books: TradingBookStore,
    private val artifacts: TradingSourceArtifactReader,
) {
    fun fail(run: NormalizationRun, dispatch: NormalizationDispatch, session: TradingImportSession,
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
        val failed = session.failValidation(run.id.value, run.input.deletionGeneration, failure, now)
            ?: return
        if (!sessions.update(failed)) throw IllegalStateException("Session transition raced")
        runs.audit(run.id.value, "PROCESSING_FAILED", safeCode, now)
    }

    fun isCurrent(run: NormalizationRun, session: TradingImportSession): Boolean =
        !session.deletionPending && session.status == TradingImportStatus.VALIDATING &&
            session.activeNormalizationRunId == run.id &&
            session.deletionGeneration == run.input.deletionGeneration &&
            books.find(run.input.tradingBookId, MemberId(run.input.ownerId))?.status == TradingLifecycleStatus.ACTIVE

    // Reject an expired claim before external work; runtime CAS remains the final write fence.
    fun hasLease(dispatch: NormalizationDispatch, workerId: String, now: Instant): Boolean =
        dispatch.leaseOwner == workerId && dispatch.leaseExpiresAt?.isAfter(now) == true

    fun safeCode(code: String): String = code.takeIf { it in SAFE_CODES } ?: "INTERNAL_ERROR"

    private companion object {
        val SAFE_CODES = setOf("SOURCE_ARTIFACT_EXPIRED", "ARTIFACT_IDENTITY_CONFLICT",
            "RESULT_SCHEMA_UNSUPPORTED", "RESULT_VERSION_PIN_MISMATCH", "INTERNAL_ERROR")
    }
}
