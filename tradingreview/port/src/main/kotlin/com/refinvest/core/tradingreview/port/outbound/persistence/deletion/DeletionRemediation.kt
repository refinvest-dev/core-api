package com.refinvest.core.tradingreview.port.outbound.persistence.deletion

import com.refinvest.core.tradingreview.domain.valueobject.DeletionRequestId
import java.time.Instant

data class DeletionRemediation(val failedId: DeletionRequestId, val newId: DeletionRequestId, val observedAt: Instant)
