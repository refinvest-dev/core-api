package com.refinvest.core.tradingreview.domain

import com.refinvest.core.shared.kernel.member.MemberId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import java.time.Instant

class TradingAccountBookTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z")

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
