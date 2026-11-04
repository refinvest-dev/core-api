package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.TradingAccount
import com.refinvest.core.tradingreview.domain.valueobject.*

internal object TradingAccountPersistenceMapper {
    fun toEntity(domain: TradingAccount): TradingAccountJpaEntity = TradingAccountJpaEntity(
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

    fun toDomain(entity: TradingAccountJpaEntity): TradingAccount = TradingAccount.restore(
        id = TradingAccountId(entity.id), owner = MemberId(entity.ownerMemberId),
        venue = TradingVenue.valueOf(entity.venue), name = entity.displayName,
        status = TradingLifecycleStatus.valueOf(entity.status), generation = entity.deletionGeneration,
        createdAt = entity.createdAt, updatedAt = entity.updatedAt, version = entity.version,
    )

    fun apply(domain: TradingAccount, entity: TradingAccountJpaEntity) {
        entity.displayName = domain.displayName
        entity.status = domain.status.name
        entity.deletionGeneration = domain.deletionGeneration
        entity.updatedAt = domain.updatedAt
    }

}
