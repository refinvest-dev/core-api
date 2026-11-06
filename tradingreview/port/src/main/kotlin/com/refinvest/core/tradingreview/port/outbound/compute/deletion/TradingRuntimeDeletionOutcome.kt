package com.refinvest.core.tradingreview.port.outbound.compute.deletion

import com.refinvest.core.tradingreview.domain.valueobject.*
import java.time.Duration
import java.time.Instant

sealed interface TradingRuntimeDeletionOutcome {
    data class Pending(val retryAfter: Duration) : TradingRuntimeDeletionOutcome
    data class Absent(val verifiedAt: Instant) : TradingRuntimeDeletionOutcome
}
