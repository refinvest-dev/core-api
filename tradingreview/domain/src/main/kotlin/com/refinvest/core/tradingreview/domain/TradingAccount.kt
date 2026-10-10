package com.refinvest.core.tradingreview.domain

import com.refinvest.core.shared.kernel.member.MemberId
import java.time.Instant

@JvmInline value class TradingAccountId(val value: Long)
@JvmInline value class TradingBookId(val value: Long)
enum class TradingVenue { BINANCE }
enum class TradingProductFamily { LINEAR_PERPETUAL }
enum class TradingPositionMode { ONE_WAY }
enum class TradingSettlementAsset { USDT }
enum class TradingLifecycleStatus { ACTIVE, ARCHIVED, DELETION_PENDING, DELETION_FAILED }

@ConsistentCopyVisibility
data class TradingAccount private constructor(
    val id: TradingAccountId,
    val owner: MemberId,
    val venue: TradingVenue,
    val displayName: String,
    val status: TradingLifecycleStatus,
    val deletionGeneration: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
) {
    fun rename(name: String, now: Instant): TradingAccount {
        require(status == TradingLifecycleStatus.ACTIVE) { "Account is not active" }
        return copy(displayName = validName(name), updatedAt = now)
    }

    fun requestDeletion(now: Instant): TradingAccount {
        require(status == TradingLifecycleStatus.ACTIVE || status == TradingLifecycleStatus.ARCHIVED) { "Deletion already requested" }
        return copy(status = TradingLifecycleStatus.DELETION_PENDING, deletionGeneration = deletionGeneration + 1, updatedAt = now)
    }

    companion object {
        fun create(id: TradingAccountId, owner: MemberId, venue: TradingVenue, name: String, now: Instant) =
            TradingAccount(id, owner, venue, validName(name), TradingLifecycleStatus.ACTIVE, 0, now, now, 0)

        fun restore(id: TradingAccountId, owner: MemberId, venue: TradingVenue, name: String,
                    status: TradingLifecycleStatus, generation: Long, createdAt: Instant, updatedAt: Instant, version: Long) =
            TradingAccount(id, owner, venue, validName(name), status, generation, createdAt, updatedAt, version)

        private fun validName(value: String): String {
            require(value.isNotEmpty() && value.length <= 100) { "Invalid display name" }
            return value
        }
    }
}
