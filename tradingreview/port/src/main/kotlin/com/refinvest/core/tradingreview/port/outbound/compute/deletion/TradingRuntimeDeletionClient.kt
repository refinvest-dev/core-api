package com.refinvest.core.tradingreview.port.outbound.compute.deletion

import com.refinvest.core.tradingreview.domain.valueobject.*
import java.time.Duration
import java.time.Instant

interface TradingRuntimeDeletionClient {
    fun purge(command: TradingRuntimeDeletion): TradingRuntimeDeletionOutcome
}
