package com.refinvest.core.tradingreview.application.ownerquery

import com.refinvest.core.tradingreview.port.common.ownerquery.OwnerMetadataCall
import com.refinvest.core.tradingreview.port.common.ownerquery.OwnerMetadataFailure
import com.refinvest.core.tradingreview.port.common.ownerquery.OwnerMetadataResult
import com.refinvest.core.tradingreview.port.inbound.ownerquery.OwnerMetadataQuery
import com.refinvest.core.tradingreview.port.inbound.ownerquery.ProxyOwnerMetadataUseCase
import com.refinvest.core.tradingreview.port.outbound.ownerquery.OwnerMetadataReader
import com.refinvest.core.tradingreview.port.outbound.ownerquery.OwnerQueryDelegationIssuer
import com.refinvest.core.tradingreview.port.outbound.ownerquery.OwnerQueryDelegationUnavailable
import java.time.Clock
import java.time.Duration

// Explicit Core boundary preparation. No legacy business Get, persistence or transaction is involved.
class ProxyOwnerMetadataService(
    private val issuer: OwnerQueryDelegationIssuer,
    private val reader: OwnerMetadataReader,
    private val requestTimeout: Duration,
    private val clock: Clock,
    private val nanoTime: () -> Long = System::nanoTime,
) : ProxyOwnerMetadataUseCase {
    init {
        require(requestTimeout > Duration.ZERO && requestTimeout <= Duration.ofSeconds(60)) { "Invalid owner query budget" }
    }

    override fun execute(query: OwnerMetadataQuery): OwnerMetadataCall {
        val id = query.resourceId.toLongOrNull()
        if (id == null || id <= 0 || id.toString() != query.resourceId) {
            return failure(OwnerMetadataFailure.INVALID_REQUEST)
        }

        val started = nanoTime()
        val delegation = try {
            issuer.issue()
        } catch (_: OwnerQueryDelegationUnavailable) {
            return failure(OwnerMetadataFailure.UPSTREAM_SERVICE_ERROR)
        }

        val elapsed = nanoTime() - started
        val remaining = requestTimeout.minusNanos(elapsed.coerceAtLeast(0))
        val tokenBudget = Duration.between(clock.instant(), delegation.expiresAt)
        val budget = minOf(remaining, tokenBudget)
        if (elapsed < 0 || budget <= Duration.ZERO) {
            return failure(OwnerMetadataFailure.REQUEST_DEADLINE_EXCEEDED)
        }

        return reader.read(
            OwnerMetadataReader.Request(query.resource, query.resourceId, query.traceParent, query.traceState), delegation, budget,
        )
    }

    private fun failure(kind: OwnerMetadataFailure) = OwnerMetadataCall.completed(OwnerMetadataResult.Failure(kind))
}
