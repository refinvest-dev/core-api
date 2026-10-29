package com.refinvest.core.tradingreview.domain

import com.refinvest.core.tradingreview.domain.valueobject.TradingAccountId
import com.refinvest.core.tradingreview.domain.valueobject.TradingBookId
import com.refinvest.core.tradingreview.domain.valueobject.TradingVenue
import com.refinvest.core.tradingreview.domain.valueobject.TradingProductFamily
import com.refinvest.core.tradingreview.domain.valueobject.TradingPositionMode
import com.refinvest.core.tradingreview.domain.valueobject.TradingSettlementAsset

import com.refinvest.core.shared.kernel.member.MemberId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class TradingImportSessionTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val account = TradingAccount.create(TradingAccountId(1), MemberId(7), TradingVenue.BINANCE, "A", now)
    private val book = TradingBook.create(TradingBookId(2), account, "B", TradingProductFamily.LINEAR_PERPETUAL,
        TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "Asia/Seoul", now)
    private val session = TradingImportSession.create(TradingImportSessionId(3), book, "UTC", "Asia/Seoul",
        TradingPositionMode.ONE_WAY, TradingDataPolicy(), "exact", now)

    @Test
    fun sessionPinsOwnerBookTimezoneAndMode() {
        assertEquals(book.owner, session.owner)
        assertEquals(book.id, session.bookId)
        assertEquals("UTC", session.sourceTimezone)
        assertEquals("Asia/Seoul", session.reviewTimezone)
        assertEquals(TradingPositionMode.ONE_WAY, session.declaredPositionMode)
        assertEquals("TRADING_DATA_POLICY_V1", session.dataPolicyVersion)
        assertFailsWith<IllegalArgumentException> {
            TradingImportSession.create(TradingImportSessionId(4), book, "+09:00", "Asia/Seoul",
                TradingPositionMode.ONE_WAY, TradingDataPolicy(), "exact", now)
        }
    }

    @Test
    fun readinessUsesActiveRolesAndDeletionGeneration() {
        assertFalse(session.canValidate(emptyList()))
        assertEquals(2, session.missingArtifactRoles(emptyList()).size)
        val trade = artifact(4, TradingArtifactRole.TRADE_HISTORY)
        val position = artifact(5, TradingArtifactRole.POSITION_HISTORY)
        assertFalse(session.canValidate(listOf(trade)))
        assertTrue(session.canValidate(listOf(trade, position)))
        assertFalse(session.canValidate(listOf(trade, position.copy(retentionStatus = TradingArtifactRetentionStatus.DELETION_PENDING))))
        val deleting = session.requestDeletion(now.plusSeconds(1))
        assertEquals(1, deleting.deletionGeneration)
        assertFalse(deleting.canUpload())
        assertFalse(deleting.canValidate(listOf(trade, position)))
        assertFailsWith<IllegalArgumentException> { deleting.requestDeletion(now.plusSeconds(2)) }
    }

    private fun artifact(id: Long, role: TradingArtifactRole) = TradingSourceArtifact(
        TradingSourceArtifactId(id), session.id, role, null, "text/csv", 1, "0".repeat(64), "AUTO",
        "objects/00000000-0000-0000-0000-000000000001", byteArrayOf(1), "test",
        TradingArtifactRetentionStatus.ACTIVE, now, now.plusSeconds(86400), null, null, 0)
}
