package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.TradingBook
import com.refinvest.core.tradingreview.domain.valueobject.*

internal object TradingBookPersistenceMapper {
    fun toRecord(domain: TradingBook): TradingBookRecord = TradingBookRecord(
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

    fun toDomain(record: TradingBookRecord): TradingBook = TradingBook.restore(
        id = TradingBookId(record.id), accountId = TradingAccountId(record.accountId), owner = MemberId(record.ownerMemberId),
        venue = TradingVenue.valueOf(record.venue), name = record.displayName,
        family = TradingProductFamily.valueOf(record.productFamily), mode = TradingPositionMode.valueOf(record.positionMode),
        asset = TradingSettlementAsset.valueOf(record.settlementAsset), timezone = record.reviewTimezone,
        status = TradingLifecycleStatus.valueOf(record.status), generation = record.deletionGeneration,
        createdAt = record.createdAt, updatedAt = record.updatedAt, version = record.version,
        latestLedgerRevisionId = record.latestLedgerRevisionId?.let(::LedgerRevisionId),
    )
}
