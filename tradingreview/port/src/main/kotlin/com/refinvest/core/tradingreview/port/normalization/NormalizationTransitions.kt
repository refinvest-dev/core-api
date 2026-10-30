package com.refinvest.core.tradingreview.port.normalization

import com.refinvest.core.tradingreview.domain.importsession.NormalizationDispatch
import com.refinvest.core.tradingreview.domain.valueobject.NormalizationRunId
import java.time.Instant

data class NormalizationDispatchLease(val dispatchId: String, val owner: String, val observedAt: Instant)
data class NormalizationGrantAttemptAdvance(
    val runId: NormalizationRunId, val lease: NormalizationDispatchLease,
    val expectedToken: String, val nextToken: String,
)
data class NormalizationJobRetry(
    val runId: NormalizationRunId, val lease: NormalizationDispatchLease,
    val nextDispatch: NormalizationDispatch, val nextToken: String, val failureCode: String,
)
data class NormalizationSubmissionReceipt(
    val runId: NormalizationRunId, val lease: NormalizationDispatchLease,
    val generation: Long, val attemptToken: String, val jobId: String,
)
