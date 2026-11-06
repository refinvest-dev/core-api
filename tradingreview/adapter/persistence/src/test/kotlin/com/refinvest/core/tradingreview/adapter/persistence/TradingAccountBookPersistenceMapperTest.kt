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
            val record = TradingAccountPersistenceMapper.toEntity(source)
            val result = TradingAccountPersistenceMapper.toDomain(record)
            assertSnapshotEquals(record, TradingAccountPersistenceMapper.toEntity(result))
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

        for (status in TradingLifecycleStatus.entries) for (pointer in listOf(null, UUID.randomUUID())) {
            val stored = TradingBookPersistenceMapper.toEntity(original).apply {
                this.status = status.name
                version = 17
                latestLedgerRevisionId = pointer
                deletionGeneration = if (status in setOf(TradingLifecycleStatus.ACTIVE, TradingLifecycleStatus.ARCHIVED)) 0 else 1
            }
            val result = TradingBookPersistenceMapper.toDomain(stored)
            assertSnapshotEquals(stored, TradingBookPersistenceMapper.toEntity(result))
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
        val stored = TradingAccountPersistenceMapper.toEntity(account)
        assertFailsWith<IllegalArgumentException> { TradingAccountPersistenceMapper.toDomain(stored.changed { it.version = -1 }) }
        assertFailsWith<IllegalArgumentException> { TradingAccountPersistenceMapper.toDomain(stored.changed { it.status = "DELETION_PENDING" }) }
        assertFailsWith<IllegalArgumentException> { TradingAccountPersistenceMapper.toDomain(stored.changed { it.venue = "UNKNOWN" }) }
        val book = TradingBookPersistenceMapper.toEntity(TradingBook.create(TradingBookId(2), account, "B",
            TradingProductFamily.LINEAR_PERPETUAL, TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", time))
        assertFailsWith<IllegalArgumentException> { TradingBookPersistenceMapper.toDomain(book.changed { it.reviewTimezone = "+09:00" }) }
        assertFailsWith<IllegalArgumentException> { TradingBookPersistenceMapper.toDomain(book.changed { it.deletionGeneration = -1 }) }
        assertFailsWith<IllegalArgumentException> { TradingBookPersistenceMapper.toDomain(book.changed { it.updatedAt = time.minusSeconds(1) }) }
    }

    private fun TradingAccountJpaEntity.changed(change: (TradingAccountJpaEntity) -> Unit) =
        TradingAccountPersistenceMapper.toEntity(TradingAccountPersistenceMapper.toDomain(this)).also(change)

    private fun TradingBookJpaEntity.changed(change: (TradingBookJpaEntity) -> Unit) =
        TradingBookPersistenceMapper.toEntity(TradingBookPersistenceMapper.toDomain(this)).also(change)

    private fun assertSnapshotEquals(expected: Any, actual: Any) {
        expected.javaClass.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }.forEach {
            it.isAccessible = true
            assertEquals(it.get(expected), it.get(actual), it.name)
        }
    }

}
