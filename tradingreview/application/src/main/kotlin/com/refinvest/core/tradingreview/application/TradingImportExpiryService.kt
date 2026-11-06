package com.refinvest.core.tradingreview.application

import com.refinvest.core.tradingreview.port.inbound.retention.expire.ExpireTradingImportSourcesUseCase
import com.refinvest.core.tradingreview.port.outbound.identity.TradingIdGenerator
import com.refinvest.core.tradingreview.port.outbound.persistence.account.TradingAccountStore
import com.refinvest.core.tradingreview.port.outbound.persistence.book.TradingBookStore
import com.refinvest.core.tradingreview.port.outbound.persistence.deletion.DeletionRequestStore
import com.refinvest.core.tradingreview.port.outbound.persistence.importsession.TradingImportSessionStore
import com.refinvest.core.tradingreview.port.outbound.persistence.importsession.TradingRawExpiryCandidates

import com.refinvest.core.tradingreview.domain.DeletionRequest
import com.refinvest.core.tradingreview.domain.valueobject.*
import java.time.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

@Service
class TradingImportExpiryService(
    private val candidates: TradingRawExpiryCandidates,
    private val accounts: TradingAccountStore,
    private val books: TradingBookStore,
    private val sessions: TradingImportSessionStore,
    private val deletions: DeletionRequestStore,
    private val ids: TradingIdGenerator,
    private val transactions: TransactionTemplate,
    private val clock: Clock,
) : ExpireTradingImportSourcesUseCase {
    override fun expireDue(): Int {
        var expired = 0
        for (candidate in candidates.due(clock.instant(), 20)) {
            val applied = transactions.execute {
                if (accounts.find(candidate.accountId, candidate.owner, true)?.status != TradingLifecycleStatus.ACTIVE)
                    return@execute false
                if (books.find(candidate.bookId, candidate.owner, true)?.status != TradingLifecycleStatus.ACTIVE)
                    return@execute false
                val session = sessions.find(candidate.sessionId, candidate.owner, true) ?: return@execute false
                val at = clock.instant() // After parent and Session locks.
                val source = session.artifacts.filter { !at.isBefore(it.rawExpiresAt) }
                    .minWithOrNull(compareBy({ it.rawExpiresAt }, { it.id.value })) ?: return@execute false
                val changed = session.expireRawSource(source.id, at) ?: return@execute false
                check(sessions.update(changed)) { "Raw expiry raced" }
                // A real policy-triggered raw-only request reuses durable cleanup, including unknown creates.
                deletions.insert(DeletionRequest.request(ids.deletionId(), session.owner, DeletionScope.rawArtifact(source.id),
                    changed.deletionGeneration, DeletionAffectedResources(0, 0, 0, 1), at))
                true
            } == true
            if (applied) expired++
        }
        return expired
    }
}
