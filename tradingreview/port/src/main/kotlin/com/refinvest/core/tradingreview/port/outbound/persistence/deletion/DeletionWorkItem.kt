package com.refinvest.core.tradingreview.port.outbound.persistence.deletion

import com.refinvest.core.tradingreview.port.outbound.compute.deletion.TradingRuntimeDeletion
import com.refinvest.core.tradingreview.port.outbound.storage.workspace.TradingArtifactScope

import com.refinvest.core.tradingreview.domain.DeletionRequest
import com.refinvest.core.tradingreview.domain.valueobject.*
import java.time.Duration
import java.time.Instant

sealed interface DeletionWorkItem {
    data class Runtime(val command: TradingRuntimeDeletion) : DeletionWorkItem
    data class Copies(val scope: TradingArtifactScope) : DeletionWorkItem
    data class Revision(val id: LedgerRevisionId) : DeletionWorkItem
    data class Object(val artifactId: TradingSourceArtifactId, val key: String?) : DeletionWorkItem
    data object LegacyCopies : DeletionWorkItem
}
