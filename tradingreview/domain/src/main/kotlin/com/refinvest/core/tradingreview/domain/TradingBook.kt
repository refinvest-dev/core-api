package com.refinvest.core.tradingreview.domain

import com.refinvest.core.shared.kernel.member.MemberId
import java.time.Instant
import java.time.ZoneId

@ConsistentCopyVisibility
data class TradingBook private constructor(
    val id: TradingBookId,
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
) {
    fun rename(name: String, now: Instant): TradingBook {
        require(status == TradingLifecycleStatus.ACTIVE) { "Book is not active" }
        return copy(displayName = validName(name), updatedAt = now)
    }

    fun requestDeletion(now: Instant): TradingBook {
        require(status == TradingLifecycleStatus.ACTIVE) { "Deletion already requested" }
        return copy(status = TradingLifecycleStatus.DELETION_PENDING, deletionGeneration = deletionGeneration + 1, updatedAt = now)
    }

    companion object {
        fun create(id: TradingBookId, account: TradingAccount, name: String, family: TradingProductFamily,
                   mode: TradingPositionMode, asset: TradingSettlementAsset, timezone: String, now: Instant): TradingBook {
            require(account.status == TradingLifecycleStatus.ACTIVE) { "Account is not active" }
            require(timezone in ZoneId.getAvailableZoneIds()) { "Invalid IANA review timezone" }
            return TradingBook(id, account.id, account.owner, account.venue, validName(name), family, mode,
                asset, timezone, TradingLifecycleStatus.ACTIVE, 0, now, now, 0)
        }

        fun restore(id: TradingBookId, accountId: TradingAccountId, owner: MemberId, venue: TradingVenue,
                    name: String, family: TradingProductFamily, mode: TradingPositionMode, asset: TradingSettlementAsset,
                    timezone: String, status: TradingLifecycleStatus, generation: Long, createdAt: Instant,
                    updatedAt: Instant, version: Long) = TradingBook(id, accountId, owner, venue, validName(name),
            family, mode, asset, timezone, status, generation, createdAt, updatedAt, version)

        private fun validName(value: String): String {
            require(value.isNotEmpty() && value.length <= 100) { "Invalid display name" }
            return value
        }
    }
}
