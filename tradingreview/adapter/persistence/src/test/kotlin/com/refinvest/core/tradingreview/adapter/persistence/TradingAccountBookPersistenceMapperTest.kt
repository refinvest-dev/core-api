package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.TradingAccount
import com.refinvest.core.tradingreview.domain.TradingBook
import com.refinvest.core.tradingreview.domain.valueobject.*
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TradingAccountBookPersistenceMapperTest {
    private val time = Instant.parse("2026-10-30T00:00:00.123456789Z")

    @Test
    fun accountSnapshotsKeepEveryLifecycleStateAndImmutableIdentity() {
        TradingLifecycleStatus.entries.forEach { status ->
            val generation = if (status in setOf(TradingLifecycleStatus.ACTIVE, TradingLifecycleStatus.ARCHIVED)) 0L else 1L
            val source = TradingAccount.restore(TradingAccountId(9007199254740993L), MemberId(7), TradingVenue.BINANCE,
                "Account", status, generation, time, time.plusSeconds(1), 42)
            val record = TradingAccountPersistenceMapper.toRecord(source)
            val result = TradingAccountPersistenceMapper.toDomain(record)
            assertEquals(record, TradingAccountPersistenceMapper.toRecord(result))
            assertEquals(source.id, result.id)
            assertEquals(source.status, result.status)
            assertEquals(generation, result.deletionGeneration)
            assertEquals(42L, result.version)
            assertEquals(time, result.createdAt)
        }
    }

    @Test
    fun bookSnapshotsPreserveThePublicationReferenceWithoutLoadingALedger() {
        val account = TradingAccount.create(TradingAccountId(1), MemberId(7), TradingVenue.BINANCE, "A", time)
        val original = TradingBook.create(TradingBookId(9007199254740993L), account, "B",
            TradingProductFamily.LINEAR_PERPETUAL, TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "Asia/Seoul", time)
        val record = TradingBookPersistenceMapper.toRecord(original)
        for (status in TradingLifecycleStatus.entries) for (pointer in listOf(null, UUID.randomUUID())) {
            val stored = record.copy(status = status.name, version = 17, latestLedgerRevisionId = pointer,
                deletionGeneration = if (status in setOf(TradingLifecycleStatus.ACTIVE, TradingLifecycleStatus.ARCHIVED)) 0 else 1)
            val result = TradingBookPersistenceMapper.toDomain(stored)
            assertEquals(stored, TradingBookPersistenceMapper.toRecord(result))
            assertEquals(pointer, result.latestLedgerRevisionId?.value)
            assertEquals(original.accountId, result.accountId)
            assertEquals("Asia/Seoul", result.reviewTimezone)
            if (status == TradingLifecycleStatus.ACTIVE) {
                assertEquals(result.latestLedgerRevisionId, result.rename("Renamed", time).latestLedgerRevisionId)
                assertEquals(result.latestLedgerRevisionId, result.requestDeletion(time).latestLedgerRevisionId)
                assertEquals("B", result.displayName)
            }
        }
    }

    @Test
    fun malformedStoredStateIsRejectedRatherThanDefaultedOrReplayed() {
        val account = TradingAccount.create(TradingAccountId(1), MemberId(7), TradingVenue.BINANCE, "A", time)
        val stored = TradingAccountPersistenceMapper.toRecord(account)
        assertFailsWith<IllegalArgumentException> { TradingAccountPersistenceMapper.toDomain(stored.copy(version = -1)) }
        assertFailsWith<IllegalArgumentException> { TradingAccountPersistenceMapper.toDomain(stored.copy(status = "DELETION_PENDING")) }
        assertFailsWith<IllegalArgumentException> { TradingAccountPersistenceMapper.toDomain(stored.copy(venue = "UNKNOWN")) }
        val book = TradingBookPersistenceMapper.toRecord(TradingBook.create(TradingBookId(2), account, "B",
            TradingProductFamily.LINEAR_PERPETUAL, TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", time))
        assertFailsWith<IllegalArgumentException> { TradingBookPersistenceMapper.toDomain(book.copy(reviewTimezone = "+09:00")) }
        assertFailsWith<IllegalArgumentException> { TradingBookPersistenceMapper.toDomain(book.copy(deletionGeneration = -1)) }
        assertFailsWith<IllegalArgumentException> { TradingBookPersistenceMapper.toDomain(book.copy(updatedAt = time.minusSeconds(1))) }
    }
}
