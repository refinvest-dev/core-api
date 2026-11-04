package com.refinvest.core.tradingreview.port.outbound.persistence.deletion

import com.refinvest.core.tradingreview.domain.DeletionRequest

interface TradingDeletionRemediation {
    /** Locks the failed request, preserves its evidence and atomically creates or replays the remediation. */
    fun remediate(command: DeletionRemediation): DeletionRequest
}
