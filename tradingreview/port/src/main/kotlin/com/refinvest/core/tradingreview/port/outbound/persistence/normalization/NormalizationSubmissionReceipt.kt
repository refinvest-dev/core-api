package com.refinvest.core.tradingreview.port.outbound.persistence.normalization

import com.refinvest.core.tradingreview.domain.importsession.NormalizationDispatch
import com.refinvest.core.tradingreview.domain.valueobject.NormalizationRunId
import java.time.Instant

data class NormalizationSubmissionReceipt(
    val runId: NormalizationRunId, val lease: NormalizationDispatchLease,
    val generation: Long, val attemptToken: String, val jobId: String,
)
