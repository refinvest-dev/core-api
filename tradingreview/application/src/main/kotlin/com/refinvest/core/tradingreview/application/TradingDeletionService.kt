package com.refinvest.core.tradingreview.application

import com.refinvest.core.tradingreview.port.inbound.deletion.process.TradingDeletionUseCase
import com.refinvest.core.tradingreview.port.inbound.deletion.remediate.RemediateTradingDeletionUseCase
import com.refinvest.core.tradingreview.port.outbound.compute.deletion.TradingRuntimeDeletionClient
import com.refinvest.core.tradingreview.port.outbound.compute.deletion.TradingRuntimeDeletionOutcome
import com.refinvest.core.tradingreview.port.outbound.identity.TradingIdGenerator
import com.refinvest.core.tradingreview.port.outbound.persistence.deletion.DeletionWorkItem
import com.refinvest.core.tradingreview.port.outbound.persistence.deletion.TradingDeletionWorkStore
import com.refinvest.core.tradingreview.port.outbound.storage.analytics.TradingAnalyticsArtifactStore
import com.refinvest.core.tradingreview.port.outbound.storage.raw.RawArtifactStorage
import com.refinvest.core.tradingreview.port.outbound.storage.workspace.LegacyTradingTemporaryCopies
import com.refinvest.core.tradingreview.port.outbound.storage.workspace.TradingArtifactScope
import com.refinvest.core.tradingreview.port.outbound.storage.workspace.TradingArtifactWorkspaces

import com.refinvest.core.tradingreview.domain.DeletionRequest
import com.refinvest.core.tradingreview.domain.valueobject.DeletionRequestId
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.util.UUID

@Service
class TradingDeletionService(
    private val store: TradingDeletionWorkStore,
    private val transactions: TransactionTemplate,
    private val raw: RawArtifactStorage,
    private val artifacts: TradingAnalyticsArtifactStore,
    private val workspaces: TradingArtifactWorkspaces,
    private val runtime: TradingRuntimeDeletionClient,
    private val legacyCopies: LegacyTradingTemporaryCopies,
    private val clock: Clock,
    private val ids: TradingIdGenerator,
) : TradingDeletionUseCase, RemediateTradingDeletionUseCase {
    private val log = System.getLogger(javaClass.name)

    override fun purgePending() {
        repeat(20) {
            val lease = transactions.execute {
                store.claim(clock.instant(), UUID.randomUUID().toString(), Duration.ofMinutes(5))
            } ?: return
            val completed = mutableListOf<DeletionWorkItem>()
            var retryAfter = Duration.ZERO
            try {
                val overdue = !clock.instant().isBefore(lease.request.requestedAt.plus(Duration.ofHours(24)))
                val plan = if (overdue) emptyList() else
                    transactions.execute { store.plan(lease, clock.instant(), 10) } ?: emptyList()
                if (overdue) log.log(System.Logger.Level.ERROR, "trading_deletion_sla_exceeded requestId=" + lease.request.id.value)
                for (item in plan) {
                    val absent = when (item) {
                        is DeletionWorkItem.Runtime -> when (val result = runtime.purge(item.command)) {
                            is TradingRuntimeDeletionOutcome.Absent -> true
                            is TradingRuntimeDeletionOutcome.Pending -> {
                                retryAfter = maxOf(retryAfter, result.retryAfter)
                                false
                            }
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
                    if (absent) completed += item
                    else retryAfter = maxOf(retryAfter, Duration.ofSeconds(5))
                }
                transactions.execute { store.finish(lease, completed, clock.instant(), retryAfter) }
            } catch (_: Exception) {
                // Preserve completed items before applying bounded error backoff. Pending is not an error.
                val saved = transactions.execute {
                    store.finish(lease, completed, clock.instant(), Duration.ZERO, failed = true)
                }
                if (saved == true) {
                    if (lease.request.attemptCount >= 4)
                        log.log(System.Logger.Level.ERROR, "trading_deletion_retry_exhausted requestId=" + lease.request.id.value)
                    else log.log(System.Logger.Level.WARNING, "trading_deletion_dependency_failure requestId=" + lease.request.id.value)
                }
                return
            }
        }
    }

    override fun remediate(failedId: DeletionRequestId): DeletionRequest = transactions.execute {
        store.remediate(failedId, ids.deletionId(), clock.instant())
    } ?: error("Remediation transaction did not return a request")
}
