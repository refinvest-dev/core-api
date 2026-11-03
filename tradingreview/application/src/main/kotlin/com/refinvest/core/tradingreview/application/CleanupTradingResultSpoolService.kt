package com.refinvest.core.tradingreview.application

import com.refinvest.core.tradingreview.port.inbound.normalization.cleanup.CleanupTradingResultSpoolUseCase
import com.refinvest.core.tradingreview.port.outbound.storage.workspace.TradingArtifactWorkspaces
import com.refinvest.core.tradingreview.port.outbound.storage.workspace.TradingResultSpoolCleanup
import java.time.Clock
import java.time.Duration
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class CleanupTradingResultSpoolService(
    private val clock: Clock,
    private val spool: TradingResultSpoolCleanup,
    private val workspaces: TradingArtifactWorkspaces,
    @Value("\${refinvest.trading-review.normalization.delivery.spool-orphan-retention:PT24H}")
    private val retention: Duration,
) : CleanupTradingResultSpoolUseCase {
    init {
        require(!retention.isNegative && !retention.isZero && retention <= Duration.ofHours(24))
    }

    override fun cleanup(): Int {
        val cutoff = clock.instant().minus(retention)
        return spool.cleanupOlderThan(cutoff) + workspaces.cleanupOrphans(cutoff, 100)
    }
}
