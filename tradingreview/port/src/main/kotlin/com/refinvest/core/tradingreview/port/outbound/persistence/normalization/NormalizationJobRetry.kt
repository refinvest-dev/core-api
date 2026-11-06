package com.refinvest.core.tradingreview.port.outbound.persistence.normalization

import com.refinvest.core.tradingreview.domain.importsession.NormalizationDispatch
import com.refinvest.core.tradingreview.domain.valueobject.NormalizationRunId
import java.time.Instant

data class NormalizationJobRetry(
    val runId: NormalizationRunId, val lease: NormalizationDispatchLease,
    val nextDispatch: NormalizationDispatch, val nextToken: String, val failureCode: String,
)
