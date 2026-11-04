package com.refinvest.core.tradingreview.application.deletion

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.application.deletion.purge.DeletionExternalPurger
import com.refinvest.core.tradingreview.application.deletion.purge.PurgeTradingDeletionService
import com.refinvest.core.tradingreview.application.deletion.remediate.RemediateTradingDeletionService
import com.refinvest.core.tradingreview.domain.DeletionRequest
import com.refinvest.core.tradingreview.domain.valueobject.*
import com.refinvest.core.tradingreview.port.inbound.deletion.purge.PurgeTradingDeletionUseCase
import com.refinvest.core.tradingreview.port.inbound.deletion.remediate.RemediateTradingDeletionCommand
import com.refinvest.core.tradingreview.port.inbound.deletion.remediate.RemediateTradingDeletionUseCase
import com.refinvest.core.tradingreview.port.outbound.compute.deletion.*
import com.refinvest.core.tradingreview.port.outbound.identity.TradingIdGenerator
import com.refinvest.core.tradingreview.port.outbound.persistence.deletion.*
import com.refinvest.core.tradingreview.port.outbound.storage.analytics.TradingAnalyticsArtifactStore
import com.refinvest.core.tradingreview.port.outbound.storage.raw.RawArtifactStorage
import com.refinvest.core.tradingreview.port.outbound.storage.workspace.*
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.aop.framework.ProxyFactory
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource
import org.springframework.transaction.interceptor.TransactionInterceptor
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate

class DeletionRuntimeServiceTest {
    private val now = Instant.parse("2026-06-01T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val objectItem = DeletionWorkItem.Object(TradingSourceArtifactId(51), "objects/synthetic")
    private val revisionItem = DeletionWorkItem.Revision(LedgerRevisionId(UUID(0, 61)))

    @Test
    fun `partial external success is preserved when a later dependency fails`() {
        val fixture = Fixture(listOf(objectItem, revisionItem), failRevision = true)
        fixture.service.purgePending()
        assertEquals(listOf(objectItem), fixture.completed)
        assertEquals(true, fixture.failed)
        assertEquals(Duration.ZERO, fixture.retryAfter)
        assertEquals(listOf("delete", "exists", "revision"), fixture.externalCalls)
        assertEquals(1, fixture.claims)
    }

    @Test
    fun `runtime pending preserves its delay without counting an error and suspends the caller transaction`() {
        val root = request(now)
        val runtimeItem = DeletionWorkItem.Runtime(TradingRuntimeDeletion(NormalizationRunId(UUID(0,71).toString()),
            TradingImportSessionId(31), 0, root.id, root.deletionGeneration))
        val fixture = Fixture(listOf(runtimeItem, objectItem))
        TransactionTemplate(fixture.manager).execute {
            assertTrue(inTransaction())
            fixture.service.purgePending()
            assertTrue(inTransaction())
        }
        assertEquals(listOf(objectItem), fixture.completed)
        assertEquals(false, fixture.failed)
        assertEquals(Duration.ofSeconds(30), fixture.retryAfter)
        assertEquals(listOf("runtime", "delete", "exists"), fixture.externalCalls)
        assertEquals(2, fixture.claims)
    }

    @Test
    fun `the exact live purge SLA boundary does not plan or perform external work`() {
        val fixture = Fixture(listOf(objectItem), requestedAt = now.minusSeconds(86400))
        fixture.service.purgePending()
        assertEquals(0, fixture.plans)
        assertEquals(emptyList(), fixture.externalCalls)
        assertEquals(emptyList(), fixture.completed)
        assertEquals(false, fixture.failed)
    }

    @Test
    fun `copy scopes use the matching raw and workspace capability and null objects do not open storage`() {
        val calls = mutableListOf<String>()
        val raw = port<RawArtifactStorage> { name, _ -> assertFalse(inTransaction()); calls += "raw:$name"; true }
        val workspaces = port<TradingArtifactWorkspaces> { name, _ -> assertFalse(inTransaction()); calls += "workspace:$name"; true }
        val purger = DeletionExternalPurger(raw, port(), workspaces, port(), port<LegacyTradingTemporaryCopies> { name, _ ->
            assertEquals("absent", name); assertFalse(inTransaction()); calls += "legacy"; true
        })
        for (scope in listOf(TradingArtifactScope.RawSlot(TradingImportSessionId(31), TradingArtifactRole.TRADE_HISTORY),
                TradingArtifactScope.SessionUploads(TradingImportSessionId(31)),
                TradingArtifactScope.LegacyArtifact(TradingSourceArtifactId(51)),
                TradingArtifactScope.Run(NormalizationRunId(UUID(0,71).toString())))) {
            assertTrue(purger.purge(DeletionWorkItem.Copies(scope)).absent)
        }
        assertTrue(purger.purge(DeletionWorkItem.Object(TradingSourceArtifactId(51), null)).absent)
        assertTrue(purger.purge(DeletionWorkItem.LegacyCopies).absent)
        assertEquals(listOf("raw:purgeCopies", "raw:purgeCopies", "raw:purgeCopies", "workspace:purge", "legacy"), calls)
    }

    @Test
    fun `remediation allocates a request id and calls its independent capability inside one transaction`() {
        val manager = manager()
        val old = request(now).expire(now.plusSeconds(86400))
        val at = now.plusSeconds(86401)
        val expected = DeletionRequest.remediation(DeletionRequestId(12), old, at)
        val ids = port<TradingIdGenerator> { name, _ ->
            assertTrue(inTransaction()); assertEquals("deletionId", name); 12L
        }
        val remediation = port<TradingDeletionRemediation> { name, args ->
            assertTrue(inTransaction()); assertEquals("remediate", name)
            assertEquals(DeletionRemediation(old.id, expected.id, at), args[0])
            expected
        }
        val service = transactional<RemediateTradingDeletionUseCase>(RemediateTradingDeletionService(remediation, ids,
            Clock.fixed(at, ZoneOffset.UTC)), manager)
        val result = service.execute(RemediateTradingDeletionCommand(old.id))
        assertEquals(expected.id.value, result.id)
        assertEquals(old.deletionGeneration, result.deletionGeneration)
        assertEquals("REQUESTED", result.state)
        assertFalse(inTransaction())
    }

    private inner class Fixture(private val items: List<DeletionWorkItem>, private val failRevision: Boolean = false,
                                requestedAt: Instant = now) {
        val manager = manager()
        var claims = 0
        var plans = 0
        var completed: List<DeletionWorkItem>? = null
        var retryAfter: Duration? = null
        var failed: Boolean? = null
        val externalCalls = mutableListOf<String>()
        private val root = request(requestedAt)
        private val lease = DeletionWorkLease(root, UUID(0,81).toString(), now.plusSeconds(300))
        private val store = port<TradingDeletionWorkStore> { name, args ->
            assertTrue(inTransaction())
            when (name) {
                "claim" -> {
                    claims++
                    assertEquals(Duration.ofMinutes(5), args[2])
                    UUID.fromString(args[1] as String)
                    if (claims == 1) lease else null
                }
                "plan" -> { plans++; assertEquals(10, args[2]); items }
                "finish" -> {
                    assertEquals(lease, args[0])
                    @Suppress("UNCHECKED_CAST")
                    val saved = args[1] as List<DeletionWorkItem>
                    completed = saved.toList()
                    retryAfter = args[3] as Duration
                    failed = args[4] as Boolean
                    true
                }
                else -> error("Unexpected store.$name")
            }
        }
        private val raw = port<RawArtifactStorage> { name, _ ->
            assertFalse(inTransaction()); externalCalls += name
            when (name) { "delete" -> Unit; "exists" -> false; else -> error("Unexpected raw.$name") }
        }
        private val artifacts = port<TradingAnalyticsArtifactStore> { name, _ ->
            assertFalse(inTransaction()); assertEquals("purgeRevision", name); externalCalls += "revision"
            if (failRevision) throw IllegalStateException("synthetic dependency failure")
            true
        }
        private val runtime = port<TradingRuntimeDeletionClient> { name, _ ->
            assertFalse(inTransaction()); assertEquals("purge", name); externalCalls += "runtime"
            TradingRuntimeDeletionOutcome.Pending(Duration.ofSeconds(30))
        }
        val service = transactional<PurgeTradingDeletionUseCase>(PurgeTradingDeletionService(store,
            TransactionTemplate(manager), DeletionExternalPurger(raw, artifacts, port(), runtime, port()), clock), manager)
    }

    private fun request(at: Instant) = DeletionRequest.request(DeletionRequestId(11), MemberId(21),
        DeletionScope.importSession(TradingImportSessionId(31)), 1, DeletionAffectedResources(0,0,1,1), at).start(at)

    private companion object {
        fun inTransaction() = TransactionSynchronizationManager.isActualTransactionActive()
        fun manager() = object : AbstractPlatformTransactionManager() {
            override fun doGetTransaction(): Any = Any()
            override fun isExistingTransaction(transaction: Any) = inTransaction()
            override fun doBegin(transaction: Any, definition: TransactionDefinition) {}
            override fun doCommit(status: DefaultTransactionStatus) {}
            override fun doRollback(status: DefaultTransactionStatus) {}
            override fun doSuspend(transaction: Any): Any = Any()
            override fun doResume(transaction: Any?, suspendedResources: Any) {}
        }
        @Suppress("UNCHECKED_CAST")
        fun <T> transactional(target: Any, manager: AbstractPlatformTransactionManager): T {
            val advice = TransactionInterceptor().apply {
                transactionManager = manager
                transactionAttributeSource = AnnotationTransactionAttributeSource()
                afterPropertiesSet()
            }
            return ProxyFactory(target).apply { addAdvice(advice) }.getProxy() as T
        }
        inline fun <reified T> port(noinline call: (String, Array<out Any?>) -> Any? = { name, _ ->
            error("Unexpected ${T::class.java.simpleName}.$name")
        }): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            call(method.name.substringBefore('-'), args ?: emptyArray())
        } as T
    }
}
