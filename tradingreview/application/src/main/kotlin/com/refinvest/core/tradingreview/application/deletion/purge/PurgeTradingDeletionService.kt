package com.refinvest.core.tradingreview.application.deletion.purge

import com.refinvest.core.tradingreview.port.inbound.deletion.purge.PurgeTradingDeletionUseCase
import com.refinvest.core.tradingreview.port.outbound.persistence.deletion.DeletionWorkItem
import com.refinvest.core.tradingreview.port.outbound.persistence.deletion.TradingDeletionWorkStore
import java.time.Clock
import java.time.Duration
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

@Service
open class PurgeTradingDeletionService(
    private val store: TradingDeletionWorkStore,
    private val transactions: TransactionTemplate,
    private val external: DeletionExternalPurger,
    private val clock: Clock,
) : PurgeTradingDeletionUseCase {
    private val log = System.getLogger(javaClass.name)

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
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
                    val result = external.purge(item)
                    if (result.absent) completed += item
                    else retryAfter = maxOf(retryAfter, result.retryAfter, Duration.ofSeconds(5))
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

}
