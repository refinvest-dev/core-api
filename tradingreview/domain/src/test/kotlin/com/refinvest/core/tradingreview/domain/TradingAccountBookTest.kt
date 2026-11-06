package com.refinvest.core.tradingreview.domain

import com.refinvest.core.tradingreview.domain.valueobject.TradingAccountId
import com.refinvest.core.tradingreview.domain.valueobject.TradingBookId
import com.refinvest.core.tradingreview.domain.valueobject.TradingVenue
import com.refinvest.core.tradingreview.domain.valueobject.TradingProductFamily
import com.refinvest.core.tradingreview.domain.valueobject.TradingPositionMode
import com.refinvest.core.tradingreview.domain.valueobject.TradingSettlementAsset
import com.refinvest.core.tradingreview.domain.valueobject.TradingLifecycleStatus

import com.refinvest.core.shared.kernel.member.MemberId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import java.time.Instant

class TradingAccountBookTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun accountAndBookUseTheSharedAggregateAndIdentifierContracts() {
        kotlin.test.assertTrue(com.refinvest.core.common.domain.AggregateRoot::class.java.isAssignableFrom(TradingAccount::class.java))
        kotlin.test.assertTrue(com.refinvest.core.common.domain.AggregateRoot::class.java.isAssignableFrom(TradingBook::class.java))
        kotlin.test.assertTrue(com.refinvest.core.common.domain.Identifier::class.java.isAssignableFrom(TradingAccountId::class.java))
        kotlin.test.assertTrue(com.refinvest.core.common.domain.Identifier::class.java.isAssignableFrom(TradingBookId::class.java))
    }

    @Test
    fun accountRestorationRejectsInvalidLifecycleMetadata() {
        fun restore(status: TradingLifecycleStatus = TradingLifecycleStatus.ACTIVE, generation: Long = 0,
                    version: Long = 0, updatedAt: Instant = now) = TradingAccount.restore(
            TradingAccountId(1), MemberId(2), TradingVenue.BINANCE, "A", status, generation, now, updatedAt, version)
        assertFailsWith<IllegalArgumentException> { restore(generation = -1) }
        assertFailsWith<IllegalArgumentException> { restore(version = -1) }
        assertFailsWith<IllegalArgumentException> { restore(generation = 1) }
        assertFailsWith<IllegalArgumentException> { restore(status = TradingLifecycleStatus.DELETION_PENDING) }
        assertFailsWith<IllegalArgumentException> { restore(status = TradingLifecycleStatus.DELETION_FAILED) }
        assertFailsWith<IllegalArgumentException> { restore(updatedAt = now.minusSeconds(1)) }
    }

    @Test
    fun bookRestorationCannotBypassTimezoneAndLifecycleValidation() {
        fun restore(timezone: String = "UTC", status: TradingLifecycleStatus = TradingLifecycleStatus.ACTIVE,
                    generation: Long = 0, version: Long = 0, updatedAt: Instant = now) = TradingBook.restore(
            TradingBookId(3), TradingAccountId(1), MemberId(2), TradingVenue.BINANCE, "B",
            TradingProductFamily.LINEAR_PERPETUAL, TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT,
            timezone, status, generation, now, updatedAt, version)
        assertFailsWith<IllegalArgumentException> { restore(timezone = "+09:00") }
        assertFailsWith<IllegalArgumentException> { restore(timezone = "not-a-zone") }
        assertFailsWith<IllegalArgumentException> { restore(generation = -1) }
        assertFailsWith<IllegalArgumentException> { restore(version = -1) }
        assertFailsWith<IllegalArgumentException> { restore(generation = 1) }
        assertFailsWith<IllegalArgumentException> { restore(status = TradingLifecycleStatus.DELETION_PENDING) }
        assertFailsWith<IllegalArgumentException> { restore(updatedAt = now.minusSeconds(1)) }
    }

    @Test
    fun `account identity is preserved across rename and deletion`() {
        val original = TradingAccount.create(TradingAccountId(1), MemberId(2), TradingVenue.BINANCE, "Primary", now)
        val renamed = original.rename("Other", now.plusSeconds(1))
        assertEquals(original.owner, renamed.owner)
        assertEquals(original.venue, renamed.venue)
        assertEquals("Other", renamed.displayName)
        val deleting = renamed.requestDeletion(now.plusSeconds(2))
        assertEquals(1, deleting.deletionGeneration)
        assertEquals(TradingLifecycleStatus.DELETION_PENDING, deleting.status)
        assertFailsWith<IllegalArgumentException> { deleting.rename("Again", now) }
        assertFailsWith<IllegalArgumentException> { deleting.requestDeletion(now) }
    }

    @Test
    fun `book pins account and reconstruction identity`() {
        val account = TradingAccount.create(TradingAccountId(1), MemberId(2), TradingVenue.BINANCE, "Primary", now)
        val book = TradingBook.create(TradingBookId(3), account, "Futures", TradingProductFamily.LINEAR_PERPETUAL,
            TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "Asia/Seoul", now)
        val renamed = book.rename("New", now.plusSeconds(1))
        assertEquals(book.accountId, renamed.accountId)
        assertEquals(book.productFamily, renamed.productFamily)
        assertEquals(book.positionMode, renamed.positionMode)
        assertEquals(book.settlementAsset, renamed.settlementAsset)
        assertEquals(book.owner, renamed.owner)
        val deleting = renamed.requestDeletion(now.plusSeconds(2))
        assertEquals(1, deleting.deletionGeneration)
        assertFailsWith<IllegalArgumentException> { deleting.rename("Again", now) }
        assertFailsWith<IllegalArgumentException> {
            TradingBook.create(TradingBookId(4), account.requestDeletion(now), "Blocked",
                TradingProductFamily.LINEAR_PERPETUAL, TradingPositionMode.ONE_WAY,
                TradingSettlementAsset.USDT, "UTC", now)
        }
        assertFailsWith<IllegalArgumentException> {
            TradingBook.create(TradingBookId(5), account, "Invalid timezone",
                TradingProductFamily.LINEAR_PERPETUAL, TradingPositionMode.ONE_WAY,
                TradingSettlementAsset.USDT, "+09:00", now)
        }
    }
}
