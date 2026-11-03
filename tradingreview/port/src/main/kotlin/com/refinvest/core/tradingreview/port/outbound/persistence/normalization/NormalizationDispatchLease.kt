package com.refinvest.core.tradingreview.port.outbound.persistence.normalization

import com.refinvest.core.tradingreview.domain.importsession.NormalizationDispatch
import com.refinvest.core.tradingreview.domain.valueobject.NormalizationRunId
import java.time.Instant

data class NormalizationDispatchLease(val dispatchId: String, val owner: String, val observedAt: Instant)
