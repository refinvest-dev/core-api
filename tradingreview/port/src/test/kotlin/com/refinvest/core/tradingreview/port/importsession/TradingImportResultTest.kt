package com.refinvest.core.tradingreview.port.importsession

import com.refinvest.core.tradingreview.port.inbound.importsession.TradingImportSessionResult
import com.refinvest.core.tradingreview.port.inbound.importsession.TradingSourceArtifactResult

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.TradingAccount
import com.refinvest.core.tradingreview.domain.TradingBook
import com.refinvest.core.tradingreview.domain.TradingImportSession
import com.refinvest.core.tradingreview.domain.importsession.TradingSourceArtifact
import com.refinvest.core.tradingreview.domain.valueobject.*
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TradingImportResultTest {
    private val now = Instant.parse("2026-11-03T00:00:00Z")
    private val sessionId = TradingImportSessionId(3)

    @Test
    fun `artifact readability honors the exact expiry boundary without exposing key material`() {
        val artifact = artifact(TradingArtifactRole.TRADE_HISTORY)
        val before = TradingSourceArtifactResult.fromDomain(artifact, now.plusSeconds(59))
        val atExpiry = TradingSourceArtifactResult.fromDomain(artifact, now.plusSeconds(60))
        assertTrue(before.rawReparseAvailable)
        assertFalse(atExpiry.rawReparseAvailable)
        val fields = TradingSourceArtifactResult::class.java.declaredFields.map { it.name }
        assertTrue(fields.none { it in setOf("objectKey", "wrappedDek", "keyVersion") })
    }

    @Test
    fun `session projection preserves role completeness and upload eligibility`() {
        val account = TradingAccount.create(TradingAccountId(1), MemberId(1), TradingVenue.BINANCE, "test", now)
        val book = TradingBook.create(TradingBookId(2), account, "test", TradingProductFamily.LINEAR_PERPETUAL,
            TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", now)
        val session = TradingImportSession.create(sessionId, book, "UTC", "UTC", TradingPositionMode.ONE_WAY, TradingDataPolicy(), "{}", now)
        val one = TradingImportSessionResult.fromDomain(session, listOf(artifact(TradingArtifactRole.TRADE_HISTORY)))
        assertFalse(one.canValidate)
        assertEquals(listOf(TradingArtifactRole.POSITION_HISTORY), one.missingArtifactRoles)
        val full = TradingImportSessionResult.fromDomain(session, TradingArtifactRole.entries.map(::artifact))
        assertTrue(full.canValidate)
        assertTrue(full.missingArtifactRoles.isEmpty())
        assertEquals(TradingArtifactRole.entries.sortedBy { it.name }, full.uploadedArtifactRoles)
    }

    private fun artifact(role: TradingArtifactRole) = TradingSourceArtifact.restore(
        TradingSourceArtifactId(10 + role.ordinal.toLong()), sessionId, role, null, "text/csv", 1,
        "0".repeat(64), "AUTO", "objects/test", byteArrayOf(1), "test-v1", TradingArtifactRetentionStatus.ACTIVE,
        now, now.plusSeconds(60), null, null, 0,
    )
}
