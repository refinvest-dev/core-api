package com.refinvest.core.tradingreview.application

import com.refinvest.core.tradingreview.port.inbound.retention.purge.PurgeExpiredTradingRawArtifactsUseCase
import com.refinvest.core.tradingreview.port.inbound.retention.purge.TradingRawRetentionResult
import com.refinvest.core.tradingreview.port.outbound.persistence.importsession.RawArtifactPurgeOutcome
import com.refinvest.core.tradingreview.port.outbound.persistence.importsession.RawArtifactRetentionCompletion
import com.refinvest.core.tradingreview.port.outbound.persistence.importsession.TradingArtifactRetention
import com.refinvest.core.tradingreview.port.outbound.storage.raw.RawArtifactStorage
import com.refinvest.core.tradingreview.port.outbound.storage.workspace.TradingArtifactScope

import java.time.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

@Service
class TradingRawRetentionService(
    private val retention: TradingArtifactRetention,
    private val storage: RawArtifactStorage,
    private val transactions: TransactionTemplate,
    private val clock: Clock,
) : PurgeExpiredTradingRawArtifactsUseCase {
    override fun purgeExpired(): TradingRawRetentionResult {
        val claims = transactions.execute { retention.claimDue(clock.instant(), 20) }.orEmpty()
        var deleted = 0
        var waiting = 0
        var failures = 0
        for (claim in claims) {
            val outcome = try {
                val artifact = claim.artifact
                val slotAbsent = storage.purgeCopies(TradingArtifactScope.RawSlot(artifact.sessionId, artifact.role))
                val legacyAbsent = storage.purgeCopies(TradingArtifactScope.LegacyArtifact(artifact.id))
                if (!slotAbsent || !legacyAbsent) RawArtifactPurgeOutcome.WAITING
                else {
                    val key = requireNotNull(artifact.objectKey)
                    storage.delete(key)
                    check(!storage.exists(key)) { "Raw object still exists" }
                    RawArtifactPurgeOutcome.ABSENT
                }
            } catch (_: Exception) {
                if (claim.failureCount >= 4) RawArtifactPurgeOutcome.EXHAUSTED else RawArtifactPurgeOutcome.RETRY
            }
            val now = clock.instant()
            val next = when (outcome) {
                RawArtifactPurgeOutcome.WAITING -> now.plusSeconds(5)
                RawArtifactPurgeOutcome.RETRY -> now.plusSeconds(minOf(5L shl minOf(claim.failureCount, 4), 60L))
                else -> null
            }
            val applied = transactions.execute { retention.finish(RawArtifactRetentionCompletion(claim, outcome, now, next)) } == true
            if (applied) when (outcome) {
                RawArtifactPurgeOutcome.ABSENT -> deleted++
                RawArtifactPurgeOutcome.WAITING -> waiting++
                else -> failures++
            }
        }
        val overdue = transactions.execute { retention.countOverdue(clock.instant()) } ?: 0L
        return TradingRawRetentionResult(overdue, deleted, waiting, failures)
    }
}
