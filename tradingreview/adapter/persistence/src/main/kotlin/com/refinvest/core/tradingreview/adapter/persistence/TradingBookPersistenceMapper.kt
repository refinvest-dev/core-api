package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.TradingBook
import com.refinvest.core.tradingreview.domain.valueobject.*

internal object TradingBookPersistenceMapper {
    fun toEntity(domain: TradingBook): TradingBookJpaEntity = TradingBookJpaEntity(
        id = domain.id.value,
        accountId = domain.accountId.value,
        ownerMemberId = domain.owner.value,
        venue = domain.venue.name,
        displayName = domain.displayName,
        productFamily = domain.productFamily.name,
        positionMode = domain.positionMode.name,
        settlementAsset = domain.settlementAsset.name,
        reviewTimezone = domain.reviewTimezone,
        status = domain.status.name,
        deletionGeneration = domain.deletionGeneration,
        createdAt = domain.createdAt,
        updatedAt = domain.updatedAt,
        version = domain.version,
        latestLedgerRevisionId = domain.latestLedgerRevisionId?.value,
    )

    fun toDomain(entity: TradingBookJpaEntity): TradingBook = TradingBook.restore(
        id = TradingBookId(entity.id), accountId = TradingAccountId(entity.accountId), owner = MemberId(entity.ownerMemberId),
        venue = TradingVenue.valueOf(entity.venue), name = entity.displayName,
        family = TradingProductFamily.valueOf(entity.productFamily), mode = TradingPositionMode.valueOf(entity.positionMode),
        asset = TradingSettlementAsset.valueOf(entity.settlementAsset), timezone = entity.reviewTimezone,
        status = TradingLifecycleStatus.valueOf(entity.status), generation = entity.deletionGeneration,
        createdAt = entity.createdAt, updatedAt = entity.updatedAt, version = entity.version,
        latestLedgerRevisionId = entity.latestLedgerRevisionId?.let(::LedgerRevisionId),
    )

    fun apply(domain: TradingBook, entity: TradingBookJpaEntity) {
        entity.displayName = domain.displayName
        entity.status = domain.status.name
        entity.deletionGeneration = domain.deletionGeneration
        entity.updatedAt = domain.updatedAt
    }

}
