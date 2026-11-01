package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.DeletionRequest
import com.refinvest.core.tradingreview.domain.valueobject.*
import java.time.Instant
import kotlin.test.*

class DeletionRequestPersistenceMapperTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val initial = DeletionRequestPersistenceMapper.toRecord(DeletionRequest.request(DeletionRequestId(3),
        MemberId(1), DeletionScope.book(TradingBookId(2)), 1, DeletionAffectedResources(0, 1), now))

    @Test
    fun allPersistedStatesRoundTripIncludingRetryAttemptAndTerminalMetadata() {
        DeletionRequestState.entries.forEach { state ->
            val row = initial.copy(state = state.name, attemptCount = 5,
                failureCode = if (state == DeletionRequestState.FAILED || state == DeletionRequestState.REQUESTED)
                    "DELETION_PARTIALLY_COMPLETED" else null,
                updatedAt = now.plusSeconds(10), completedAt = if (state == DeletionRequestState.COMPLETED) now.plusSeconds(10) else null)
            assertEquals(row, DeletionRequestPersistenceMapper.toRecord(DeletionRequestPersistenceMapper.toDomain(row)))
        }
    }

    @Test
    fun historicalSessionScopeAndUnrecordedCountersAreNotRewritten() {
        val legacy = initial.copy(targetType = "TRADING_IMPORT_SESSION", targetId = 7,
            requestedScope = "IMPORT_SUBTREE", bookCount = 0, importSessionCount = 0)
        val domain = DeletionRequestPersistenceMapper.toDomain(legacy)
        assertEquals("IMPORT_SUBTREE", domain.scope.requestedScope)
        assertEquals(0L, domain.affectedResources.importSessions)
        assertEquals(legacy, DeletionRequestPersistenceMapper.toRecord(domain))
    }

    @Test
    fun invalidScopeStateGenerationCountersAndChronologyAreRejected() {
        listOf(initial.copy(requestedScope = "RAW_ONLY"), initial.copy(state = "UNKNOWN"),
            initial.copy(targetType = "UNKNOWN"), initial.copy(deletionGeneration = 0),
            initial.copy(accountCount = -1), initial.copy(attemptCount = -1),
            initial.copy(updatedAt = now.minusSeconds(1)), initial.copy(completedAt = now),
            initial.copy(state = "COMPLETED"), initial.copy(state = "FAILED"),
            initial.copy(failureCode = "secret stack trace")).forEach { row ->
            assertFailsWith<IllegalArgumentException> { DeletionRequestPersistenceMapper.toDomain(row) }
        }
    }
    @Test
    fun partialDurableEvidenceAndVersionRoundTripWithoutUsingStateAsProof() {
        val row = initial.copy(state = "PURGING_OBJECTS", version = 17, updatedAt = now.plusSeconds(20),
            verifiedAbsent = mapOf("PRIMARY_DATA" to now.plusSeconds(1), "RAW_OBJECTS" to now.plusSeconds(20)))
        val root = DeletionRequestPersistenceMapper.toDomain(row)
        assertEquals(row, DeletionRequestPersistenceMapper.toRecord(root))
        assertFailsWith<IllegalArgumentException> { root.complete(now.plusSeconds(20)) }
        assertFailsWith<IllegalArgumentException> {
            DeletionRequestPersistenceMapper.toDomain(row.copy(verifiedAbsent = mapOf("RAW_KEYS" to now.plusSeconds(21))))
        }
    }

}
