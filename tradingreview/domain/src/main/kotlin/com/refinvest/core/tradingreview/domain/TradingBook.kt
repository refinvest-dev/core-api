package com.refinvest.core.tradingreview.domain

import com.refinvest.core.common.domain.AggregateRoot
import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.valueobject.*
import java.time.Instant
import java.time.ZoneId

class TradingBook private constructor(
    id: TradingBookId,
    val accountId: TradingAccountId,
    val owner: MemberId,
    val venue: TradingVenue,
    val displayName: String,
    val productFamily: TradingProductFamily,
    val positionMode: TradingPositionMode,
    val settlementAsset: TradingSettlementAsset,
    val reviewTimezone: String,
    val status: TradingLifecycleStatus,
    val deletionGeneration: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
    val latestLedgerRevisionId: LedgerRevisionId?,
) : AggregateRoot<TradingBookId>(id) {
    init {
        require(displayName.isNotEmpty() && displayName.length <= 100) { "Invalid display name" }
        require(reviewTimezone in ZoneId.getAvailableZoneIds()) { "Invalid IANA review timezone" }
        require(version >= 0) { "Book version must not be negative" }
        require(deletionGeneration >= 0) { "Book deletion generation must not be negative" }
        require(!updatedAt.isBefore(createdAt)) { "Book update precedes creation" }
        val deleting = status == TradingLifecycleStatus.DELETION_PENDING || status == TradingLifecycleStatus.DELETION_FAILED
        require(if (deleting) deletionGeneration > 0 else deletionGeneration == 0L) { "Book lifecycle and deletion generation disagree" }
    }

    fun rename(name: String, now: Instant): TradingBook {
        require(status == TradingLifecycleStatus.ACTIVE) { "Book is not active" }
        return TradingBook(id, accountId, owner, venue, name, productFamily, positionMode, settlementAsset,
            reviewTimezone, status, deletionGeneration, createdAt, now, version, latestLedgerRevisionId)
    }

    fun requestDeletion(now: Instant): TradingBook {
        require(status == TradingLifecycleStatus.ACTIVE) { "Deletion already requested" }
        return TradingBook(id, accountId, owner, venue, displayName, productFamily, positionMode, settlementAsset,
            reviewTimezone, TradingLifecycleStatus.DELETION_PENDING, Math.addExact(deletionGeneration, 1),
            createdAt, now, version, latestLedgerRevisionId)
    }

    companion object {
        fun create(id: TradingBookId, account: TradingAccount, name: String, family: TradingProductFamily,
                   mode: TradingPositionMode, asset: TradingSettlementAsset, timezone: String, now: Instant): TradingBook {
            require(account.status == TradingLifecycleStatus.ACTIVE) { "Account is not active" }
            return TradingBook(id, account.id, account.owner, account.venue, name, family, mode,
                asset, timezone, TradingLifecycleStatus.ACTIVE, 0, now, now, 0, null)
        }

        fun restore(id: TradingBookId, accountId: TradingAccountId, owner: MemberId, venue: TradingVenue,
                    name: String, family: TradingProductFamily, mode: TradingPositionMode, asset: TradingSettlementAsset,
                    timezone: String, status: TradingLifecycleStatus, generation: Long, createdAt: Instant,
                    updatedAt: Instant, version: Long, latestLedgerRevisionId: LedgerRevisionId? = null) =
            TradingBook(id, accountId, owner, venue, name, family, mode, asset, timezone, status, generation,
                createdAt, updatedAt, version, latestLedgerRevisionId)
    }
}
