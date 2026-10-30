package com.refinvest.core.tradingreview.domain

import com.refinvest.core.common.domain.AggregateRoot
import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.valueobject.*
import java.time.Instant

class TradingAccount private constructor(
    id: TradingAccountId,
    val owner: MemberId,
    val venue: TradingVenue,
    val displayName: String,
    val status: TradingLifecycleStatus,
    val deletionGeneration: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
) : AggregateRoot<TradingAccountId>(id) {
    init {
        require(displayName.isNotEmpty() && displayName.length <= 100) { "Invalid display name" }
        require(version >= 0) { "Account version must not be negative" }
        require(deletionGeneration >= 0) { "Account deletion generation must not be negative" }
        require(!updatedAt.isBefore(createdAt)) { "Account update precedes creation" }
        val deleting = status == TradingLifecycleStatus.DELETION_PENDING || status == TradingLifecycleStatus.DELETION_FAILED
        require(if (deleting) deletionGeneration > 0 else deletionGeneration == 0L) { "Account lifecycle and deletion generation disagree" }
    }

    fun rename(name: String, now: Instant): TradingAccount {
        require(status == TradingLifecycleStatus.ACTIVE) { "Account is not active" }
        return TradingAccount(id, owner, venue, name, status, deletionGeneration, createdAt, now, version)
    }

    fun requestDeletion(now: Instant): TradingAccount {
        require(status == TradingLifecycleStatus.ACTIVE || status == TradingLifecycleStatus.ARCHIVED) { "Deletion already requested" }
        return TradingAccount(id, owner, venue, displayName, TradingLifecycleStatus.DELETION_PENDING,
            Math.addExact(deletionGeneration, 1), createdAt, now, version)
    }

    companion object {
        fun create(id: TradingAccountId, owner: MemberId, venue: TradingVenue, name: String, now: Instant) =
            TradingAccount(id, owner, venue, name, TradingLifecycleStatus.ACTIVE, 0, now, now, 0)

        fun restore(id: TradingAccountId, owner: MemberId, venue: TradingVenue, name: String,
                    status: TradingLifecycleStatus, generation: Long, createdAt: Instant, updatedAt: Instant, version: Long) =
            TradingAccount(id, owner, venue, name, status, generation, createdAt, updatedAt, version)
    }
}
