package com.refinvest.core.tradingreview.port.outbound.storage.workspace

import com.refinvest.core.tradingreview.domain.valueobject.*
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant

interface TradingArtifactWorkspaces {
    fun acquire(scope: TradingArtifactScope): TradingArtifactLease
    /** Fence new writes, then verify existing handles and all temporary bytes are absent. */
    fun purge(scope: TradingArtifactScope): Boolean
    fun cleanupOrphans(olderThan: Instant, limit: Int): Int
}
