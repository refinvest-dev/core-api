package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.*
import com.refinvest.core.tradingreview.domain.valueobject.*
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class TradingImportSessionPersistenceMapperTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val account = TradingAccount.create(TradingAccountId(1), MemberId(7), TradingVenue.BINANCE, "A", now)
    private val book = TradingBook.create(TradingBookId(2), account, "B", TradingProductFamily.LINEAR_PERPETUAL,
        TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", now)
    private val session = TradingImportSession.create(TradingImportSessionId(3), book, "UTC", "Asia/Seoul",
        TradingPositionMode.ONE_WAY, TradingDataPolicy(), "exact-pins", now).recordUpload(
        TradingSourceArtifactId(4), TradingArtifactRole.TRADE_HISTORY, "text/csv", 3, "a".repeat(64),
        "AUTO", "objects/test", byteArrayOf(1, 2), "test-key", now)

    @Test
    fun restorationPreservesPinsFailureChildStateAndPublishedPointerWithoutReplayingActions() {
        val record = TradingImportSessionPersistenceMapper.toRecord(session)
        for (status in TradingImportStatus.entries) {
            val published = if (status == TradingImportStatus.ACCEPTED) UUID.randomUUID() else null
            val stored = record.copy(status = status.name, version = 17, activeNormalizationRunId = if (status == TradingImportStatus.RECEIVED) null else "00000000-0000-0000-0000-000000000001",
                validationStartedAt = if (status == TradingImportStatus.RECEIVED) null else now, createdLedgerRevisionId = published,
                failure = if (status in setOf(TradingImportStatus.REJECTED, TradingImportStatus.PROCESSING_FAILED))
                    TradingImportFailureRecord("STORED", false, "WAIT_FOR_RECOVERY", now) else null)
            val restored = TradingImportSessionPersistenceMapper.toDomain(stored)
            assertEquals(status, restored.status)
            assertEquals(17L, restored.version)
            assertEquals(stored.activeNormalizationRunId, restored.activeNormalizationRunId?.value)
            assertEquals(record.versionSetJson, restored.versionSetJson)
            assertEquals(published, restored.createdLedgerRevisionId?.value)
            val roundTrip = TradingImportSessionPersistenceMapper.toRecord(restored)
            assertEquals(stored.copy(artifacts = emptyList()), roundTrip.copy(artifacts = emptyList()))
            assertEquals(stored.artifacts.single().copy(wrappedDek = null), roundTrip.artifacts.single().copy(wrappedDek = null))
            assertContentEquals(stored.artifacts.single().wrappedDek, roundTrip.artifacts.single().wrappedDek)
        }
    }

    @Test
    fun restorationRejectsInvalidSessionAndArtifactCombinations() {
        val record = TradingImportSessionPersistenceMapper.toRecord(session)
        for (invalid in listOf(record.copy(version = -1), record.copy(sourceTimezone = "+09:00"),
            record.copy(deletionGeneration = -1), record.copy(deletionPending = true),
            record.copy(updatedAt = now.minusSeconds(1)), record.copy(status = "VALIDATING"),
            record.copy(versionSetJson = ""), record.copy(createdLedgerRevisionId = UUID.randomUUID()),
            record.copy(artifacts = record.artifacts + record.artifacts),
            record.copy(artifacts = record.artifacts.map { it.copy(sessionId = 999) }))) {
            assertFailsWith<IllegalArgumentException> { TradingImportSessionPersistenceMapper.toDomain(invalid) }
        }
        val child = record.artifacts.single()
        for (invalid in listOf(child.copy(version = -1), child.copy(byteSize = 0), child.copy(sha256 = "bad"),
            child.copy(wrappedDek = null), child.copy(rawExpiresAt = now), child.copy(deletedAt = now),
            child.copy(retentionStatus = "DELETED"))) {
            assertFailsWith<IllegalArgumentException> { TradingImportSessionPersistenceMapper.toDomain(invalid) }
        }
    }
}
