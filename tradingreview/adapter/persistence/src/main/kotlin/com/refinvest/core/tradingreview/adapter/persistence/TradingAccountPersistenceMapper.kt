package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.TradingAccount
import com.refinvest.core.tradingreview.domain.valueobject.*

internal object TradingAccountPersistenceMapper {
    fun toRecord(domain: TradingAccount): TradingAccountRecord = TradingAccountRecord(
        id = domain.id.value,
        ownerMemberId = domain.owner.value,
        venue = domain.venue.name,
        displayName = domain.displayName,
        status = domain.status.name,
        deletionGeneration = domain.deletionGeneration,
        createdAt = domain.createdAt,
        updatedAt = domain.updatedAt,
        version = domain.version,
    )

    fun toDomain(record: TradingAccountRecord): TradingAccount = TradingAccount.restore(
        id = TradingAccountId(record.id), owner = MemberId(record.ownerMemberId),
        venue = TradingVenue.valueOf(record.venue), name = record.displayName,
        status = TradingLifecycleStatus.valueOf(record.status), generation = record.deletionGeneration,
        createdAt = record.createdAt, updatedAt = record.updatedAt, version = record.version,
    )
}
