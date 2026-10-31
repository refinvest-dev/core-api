package com.refinvest.core.tradingreview.domain

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.valueobject.*
import java.time.Instant
import java.time.Duration
import kotlin.test.*

class DeletionRequestTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private fun request(scope: DeletionScope = DeletionScope.book(TradingBookId(2)),
                        resources: DeletionAffectedResources = DeletionAffectedResources(0, 1)) =
        DeletionRequest.request(DeletionRequestId(3), MemberId(1), scope, 1, resources, now)

    @Test
    fun newRequestsPinTheirScopeGenerationAndInitialState() {
        listOf(DeletionScope.account(TradingAccountId(1)) to DeletionAffectedResources(1, 2),
            DeletionScope.book(TradingBookId(2)) to DeletionAffectedResources(0, 1),
            DeletionScope.importSession(TradingImportSessionId(3)) to DeletionAffectedResources(0, 0, 1, 2),
            DeletionScope.rawArtifact(TradingSourceArtifactId(4)) to DeletionAffectedResources(0, 0, 0, 1)).forEach { (scope, resources) ->
            val value = request(scope, resources)
            assertEquals(scope, value.scope)
            assertEquals(resources, value.affectedResources)
            assertEquals(DeletionRequestState.REQUESTED, value.state)
            assertEquals(1L, value.deletionGeneration)
            assertEquals(0, value.attemptCount)
            assertEquals(now, value.revokedAt)
            assertEquals(now.plus(Duration.ofDays(30)), value.backupPurgeDueAt)
            assertNull(value.completedAt)
            assertNull(value.failureCode)
        }
    }

    @Test
    fun rawOnlyChecklistDoesNotPromisePrimaryOrDerivedDeletionAndCannotBeMutated() {
        val raw = request(DeletionScope.rawArtifact(TradingSourceArtifactId(4)), DeletionAffectedResources(0, 0, 0, 1))
        assertFalse(DeletionPurgeComponent.PRIMARY_DATA in raw.checklist.requiredComponents)
        assertFalse(DeletionPurgeComponent.DERIVED_RESULTS in raw.checklist.requiredComponents)
        assertTrue(DeletionPurgeComponent.RAW_KEYS in raw.checklist.requiredComponents)
        assertTrue(DeletionPurgeComponent.COMPUTE_RUNTIME in raw.checklist.requiredComponents)
        val subtree = request()
        assertTrue(subtree.checklist.requiredComponents.containsAll(DeletionPurgeComponent.entries))
        assertFailsWith<UnsupportedOperationException> { (raw.checklist.requiredComponents as MutableSet).clear() }
    }

    @Test
    fun targetScopeAndResourceCountersCannotBeCombinedArbitrarily() {
        assertFailsWith<IllegalArgumentException> { DeletionScope.restore(DeletionTargetType.TRADING_BOOK, TradingAccountId(2), "BOOK_SUBTREE") }
        assertFailsWith<IllegalArgumentException> { DeletionScope.restore(DeletionTargetType.TRADING_SOURCE_ARTIFACT, TradingSourceArtifactId(4), "BOOK_SUBTREE") }
        assertFailsWith<IllegalArgumentException> { DeletionScope.book(TradingBookId(0)) }
        assertFailsWith<IllegalArgumentException> { request(resources = DeletionAffectedResources(1, 1)) }
        assertFailsWith<IllegalArgumentException> { DeletionAffectedResources(0, -1) }
        assertFailsWith<IllegalArgumentException> { DeletionRequest.request(DeletionRequestId(0), MemberId(1),
            DeletionScope.book(TradingBookId(2)), 1, DeletionAffectedResources(0, 1), now) }
        assertFailsWith<IllegalArgumentException> { DeletionRequest.request(DeletionRequestId(3), MemberId(1),
            DeletionScope.book(TradingBookId(2)), 0, DeletionAffectedResources(0, 1), now) }
    }

    @Test
    fun restorationPreservesHistoricalLabelsCountersAndFailureWithoutInferringPurgeProgress() {
        val scope = DeletionScope.restore(DeletionTargetType.TRADING_IMPORT_SESSION, TradingImportSessionId(3), "IMPORT_SUBTREE")
        fun restore(state: DeletionRequestState, completed: Instant?, failure: String?) = DeletionRequest.restore(
            DeletionRequestId(4), MemberId(1), scope, state, 2, DeletionAffectedResources(0, 0),
            now, now, now.plusSeconds(10), now.plus(Duration.ofDays(30)), completed, failure, 5)
        val failed = restore(DeletionRequestState.FAILED, null, "DELETION_PARTIALLY_COMPLETED")
        assertEquals(5, failed.attemptCount)
        assertEquals("IMPORT_SUBTREE", failed.scope.requestedScope)
        assertEquals(0L, failed.affectedResources.importSessions)
        assertTrue(failed.state.terminal)
        val completed = restore(DeletionRequestState.COMPLETED, now.plusSeconds(10), null)
        assertEquals(failed.checklist.requiredComponents, completed.checklist.requiredComponents)
        assertFailsWith<IllegalArgumentException> { restore(DeletionRequestState.COMPLETED, null, null) }
        assertFailsWith<IllegalArgumentException> { restore(DeletionRequestState.FAILED, null, null) }
        assertFailsWith<IllegalArgumentException> { restore(DeletionRequestState.REQUESTED, now, null) }
        assertFailsWith<IllegalArgumentException> { restore(DeletionRequestState.FAILED, null, "unsafe details") }
    }
    @Test
    fun legalTransitionsRequirePrimaryProofAndEveryLiveComponentBeforeCompletion() {
        val cancelling = request().start(now)
        assertFailsWith<IllegalArgumentException> { cancelling.complete(now) }
        val primary = cancelling.beginPrimaryPurge(now)
        assertFailsWith<IllegalArgumentException> { primary.beginObjectPurge(now) }
        var objects = primary.verifyAbsent(DeletionPurgeComponent.PRIMARY_DATA, now)
            .verifyAbsent(DeletionPurgeComponent.DERIVED_RESULTS, now).beginObjectPurge(now)
        for (component in objects.checklist.requiredComponents - objects.verifiedAbsent.keys - DeletionPurgeComponent.COMPUTE_RUNTIME)
            objects = objects.verifyAbsent(component, now)
        assertFailsWith<IllegalArgumentException> { objects.complete(now) }
        val complete = objects.verifyAbsent(DeletionPurgeComponent.COMPUTE_RUNTIME, now).complete(now.plusSeconds(60))
        assertEquals(DeletionRequestState.COMPLETED, complete.state)
        assertEquals(now.plusSeconds(60).plus(Duration.ofDays(30)), complete.backupPurgeDueAt)
        assertFailsWith<IllegalArgumentException> { complete.recordFailure(now.plusSeconds(61)) }
        assertFailsWith<IllegalArgumentException> { complete.start(now.plusSeconds(61)) }
    }

    @Test
    fun retriesPreserveProofAndNeverReverseTerminalFailure() {
        var failed = request().start(now).verifyAbsent(DeletionPurgeComponent.COMPUTE_RUNTIME, now)
        repeat(5) { failed = failed.recordFailure(now.plusSeconds(it.toLong())) }
        assertEquals(DeletionRequestState.FAILED, failed.state)
        assertEquals(5, failed.attemptCount)
        assertEquals(mapOf(DeletionPurgeComponent.COMPUTE_RUNTIME to now), failed.verifiedAbsent)
        assertFailsWith<IllegalArgumentException> { failed.recordFailure(now.plusSeconds(10)) }
        assertFailsWith<IllegalArgumentException> { failed.beginPrimaryPurge(now.plusSeconds(10)) }
        assertFailsWith<UnsupportedOperationException> { (failed.verifiedAbsent as MutableMap).clear() }
    }

    @Test
    fun progressRejectsForeignComponentsWrongPhaseAndBackdatedEvidence() {
        val raw = request(DeletionScope.rawArtifact(TradingSourceArtifactId(4)), DeletionAffectedResources(0,0,0,1))
        assertFailsWith<IllegalArgumentException> { raw.verifyAbsent(DeletionPurgeComponent.RAW_KEYS, now) }
        val cancelling = raw.start(now.plusSeconds(1))
        assertFailsWith<IllegalArgumentException> { cancelling.verifyAbsent(DeletionPurgeComponent.PRIMARY_DATA, now.plusSeconds(1)) }
        assertFailsWith<IllegalArgumentException> { cancelling.verifyAbsent(DeletionPurgeComponent.COMPUTE_RUNTIME, now) }
        assertFailsWith<IllegalArgumentException> { cancelling.beginObjectPurge(now.plusSeconds(1)) }
    }

}
