package com.refinvest.core.tradingreview.application.deletion.purge

import com.refinvest.core.tradingreview.port.outbound.compute.deletion.TradingRuntimeDeletionClient
import com.refinvest.core.tradingreview.port.outbound.compute.deletion.TradingRuntimeDeletionOutcome
import com.refinvest.core.tradingreview.port.outbound.persistence.deletion.DeletionWorkItem
import com.refinvest.core.tradingreview.port.outbound.storage.analytics.TradingAnalyticsArtifactStore
import com.refinvest.core.tradingreview.port.outbound.storage.raw.RawArtifactStorage
import com.refinvest.core.tradingreview.port.outbound.storage.workspace.LegacyTradingTemporaryCopies
import com.refinvest.core.tradingreview.port.outbound.storage.workspace.TradingArtifactScope
import com.refinvest.core.tradingreview.port.outbound.storage.workspace.TradingArtifactWorkspaces
import java.time.Duration
import org.springframework.stereotype.Component

/** Executes only external absence checks. The caller records durable progress after these return. */
@Component
class DeletionExternalPurger(
    private val raw: RawArtifactStorage,
    private val artifacts: TradingAnalyticsArtifactStore,
    private val workspaces: TradingArtifactWorkspaces,
    private val runtime: TradingRuntimeDeletionClient,
    private val legacyCopies: LegacyTradingTemporaryCopies,
) {
    fun purge(item: DeletionWorkItem): Outcome {
        val absent = when (item) {
            is DeletionWorkItem.Runtime -> return when (val result = runtime.purge(item.command)) {
                is TradingRuntimeDeletionOutcome.Absent -> Outcome(true)
                is TradingRuntimeDeletionOutcome.Pending -> Outcome(false, result.retryAfter)
            }
            is DeletionWorkItem.Copies -> when (item.scope) {
                is TradingArtifactScope.RawSlot, is TradingArtifactScope.LegacyArtifact,
                is TradingArtifactScope.SessionUploads -> raw.purgeCopies(item.scope)
                else -> workspaces.purge(item.scope)
            }
            is DeletionWorkItem.Revision -> artifacts.purgeRevision(item.id.value.toString())
            is DeletionWorkItem.Object -> {
                val key = item.key
                key?.let { raw.delete(it) }
                key == null || !raw.exists(key)
            }
            DeletionWorkItem.LegacyCopies -> legacyCopies.absent()
        }
        return Outcome(absent)
    }

    data class Outcome(val absent: Boolean, val retryAfter: Duration = Duration.ZERO)
}
