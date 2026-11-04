package com.refinvest.core.tradingreview.application.normalization

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.application.normalization.poll.PollTradingNormalizationService
import com.refinvest.core.tradingreview.domain.TradingAccount
import com.refinvest.core.tradingreview.domain.TradingBook
import com.refinvest.core.tradingreview.domain.TradingImportSession
import com.refinvest.core.tradingreview.domain.importsession.*
import com.refinvest.core.tradingreview.domain.valueobject.*
import com.refinvest.core.tradingreview.port.outbound.artifactgrant.ArtifactAccessGrantIssuer
import com.refinvest.core.tradingreview.port.outbound.artifactgrant.ArtifactReadGrant
import com.refinvest.core.tradingreview.port.outbound.compute.normalization.*
import com.refinvest.core.tradingreview.port.outbound.normalization.policy.TradingRetryJitter
import com.refinvest.core.tradingreview.port.outbound.identity.TradingNormalizationIdGenerator
import com.refinvest.core.tradingreview.port.outbound.persistence.book.TradingBookStore
import com.refinvest.core.tradingreview.port.outbound.persistence.importsession.TradingImportSessionStore
import com.refinvest.core.tradingreview.port.outbound.persistence.importsession.TradingSourceArtifactReader
import com.refinvest.core.tradingreview.port.outbound.persistence.normalization.*
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper

class NormalizationWorkerServiceTest {
    @Test
    fun `lease expiry while waiting for the session lock leaves attempts and external grants untouched`() {
        for (action in listOf("START_RETRY_ATTEMPT", "REPLACE_QUEUED_GRANTS")) {
            val fixture = Fixture(action, lockDelay = 45)
            assertTrue(fixture.service.pollOne())
            assertEquals(emptyList(), fixture.writes, action)
            assertEquals(emptyList(), fixture.externalCalls, action)
            assertEquals(emptyList(), fixture.generated, action)
        }
    }

    @Test
    fun `grant deadline is evaluated after the session lock rather than before waiting`() {
        for (deadline in listOf(5L, 8L)) {
            val fixture = Fixture("START_RETRY_ATTEMPT", lockDelay = 8, deadlineSeconds = deadline)
            assertTrue(fixture.service.pollOne())
            assertEquals(listOf("markTerminal"), fixture.writes)
            assertEquals(fixture.start.plusSeconds(8), fixture.terminalAt)
            assertEquals(emptyList(), fixture.externalCalls)
            assertEquals(emptyList(), fixture.generated)
        }
    }

    @Test
    fun `grant retry uses the execution token and independent request key ports outside database transactions`() {
        val fixture = Fixture("START_RETRY_ATTEMPT", lockDelay = 1)
        assertTrue(fixture.service.pollOne())
        assertEquals(listOf("attemptToken", "grantUpdateKey"), fixture.generated)
        assertEquals(listOf("advanceAttempt", "schedulePoll"), fixture.writes)
        assertEquals(listOf("issueReadGrant", "issueReadGrant", "updateGrants"), fixture.externalCalls)
        assertEquals(fixture.nextToken, fixture.sentToken)
        assertEquals(fixture.updateKey, fixture.sentUpdateKey)
    }

    @Test
    fun `terminal job retry sends generated dispatch and attempt identities with the existing V2 pin`() {
        val fixture = Fixture("NONE", lockDelay = 1, terminalCode = "TEMPORARY_DEPENDENCY_FAILURE",
            pin = NormalizationResultVersionPin.V2)
        assertTrue(fixture.service.pollOne())
        assertEquals(listOf("attemptToken", "dispatchId"), fixture.generated)
        val retry = requireNotNull(fixture.jobRetry)
        assertEquals(fixture.nextDispatchId, retry.nextDispatch.id)
        assertEquals(fixture.nextToken, retry.nextToken)
        assertEquals(retry.runId.value, retry.nextDispatch.runId)
        assertEquals(NormalizationResultVersionPin.V2, retry.nextDispatch.resultVersionPin)
        assertEquals(emptyList(), fixture.externalCalls)
    }

    private class Fixture(
        private val action: String,
        private val lockDelay: Long,
        private val deadlineSeconds: Long = 300,
        private val terminalCode: String? = null,
        private val pin: NormalizationResultVersionPin = NormalizationResultVersionPin.V1,
    ) {
        val start = Instant.parse("2026-06-01T00:00:00Z")
        private val clock = MutableClock(start)
        val writes = mutableListOf<String>()
        val externalCalls = mutableListOf<String>()
        var terminalAt: Instant? = null
        var sentToken: String? = null
        var sentUpdateKey: String? = null
        var jobRetry: NormalizationJobRetry? = null
        private val owner = MemberId(11)
        private val account = TradingAccount.create(TradingAccountId(21), owner, TradingVenue.BINANCE, "A", start)
        private val book = TradingBook.create(TradingBookId(31), account, "B", TradingProductFamily.LINEAR_PERPETUAL,
            TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", start)
        private val runId = NormalizationRunId("00000000-0000-0000-0000-000000000001")
        private val token = "00000000-0000-0000-0000-000000000002"
        private val sessionId = TradingImportSessionId(41)
        private val versions = """{"versionSetHash":"${"f".repeat(64)}"}"""
        private val source = TradingArtifactRole.entries.mapIndexed { index, role ->
            TradingSourceArtifact.restore(TradingSourceArtifactId(51L + index), sessionId, role, null,
                "text/csv", 20, "a".repeat(64), "AUTO", "objects/$index", byteArrayOf(1), "test-key",
                TradingArtifactRetentionStatus.ACTIVE, start, start.plusSeconds(3600), null, null, 0)
        }
        private val input = NormalizationInput(book.id, sessionId, owner.value,
            source.map { NormalizationArtifactInput(it.id, it.role, it.sha256, it.byteSize, it.contentType, it.dialectCandidate) },
            "BINANCE", "LINEAR_PERPETUAL", "ONE_WAY", "USDT", "UTC", "UTC", versions, "[]", "policy-v1",
            "retry-v1", """{"policyVersion":"retry-v1","maxAttempts":3,"maxElapsedTime":"PT1M","initialDelay":"PT1S","maxDelay":"PT10S","jitter":"NONE"}""", 0)
        private fun run(attempt: String) = NormalizationRun.restore(runId, input, "a".repeat(64), "logical-key",
            NormalizationRunStatus.SUBMITTED, 1, attempt, "job-1", null, start, start, start, start, null, 0,
            resultVersionPin = pin)
        private var currentRun = run(token)
        private val session = TradingImportSession.restore(sessionId, book.id, owner, "UTC", "UTC",
            TradingPositionMode.ONE_WAY, "policy-v1", "raw-v1", "mask-v1", "BOOK_LIFECYCLE", "delete-v1",
            versions, TradingImportStatus.VALIDATING, 0, false, start, start, 0, start, runId, artifacts = source)
        private val sessions = port<TradingImportSessionStore> { name, args ->
            assertEquals("find", name)
            if (args.last() == true) clock.value = start.plusSeconds(lockDelay)
            session
        }
        private val books = port<TradingBookStore> { name, _ -> assertEquals("find", name); book }
        private val artifacts = port<TradingSourceArtifactReader> { name, _ -> assertEquals("list", name); source }
        private val runtime = port<TradingNormalizationRuntime> { name, args ->
            when (name) {
                "claimSubmitted" -> NormalizationDispatch("00000000-0000-0000-0000-000000000003", runId.value,
                    "compute-key", NormalizationDispatchStatus.SUBMITTED, 1, start, args[2] as String,
                    start.plusSeconds(45), null, start, start, resultVersionPin = pin)
                "findRun" -> currentRun
                "advanceAttempt" -> {
                    val change = args[0] as NormalizationGrantAttemptAdvance
                    assertTrue(inTransaction())
                    writes += name
                    if (change.lease.observedAt.isBefore(start.plusSeconds(45))) {
                        currentRun = run(change.nextToken)
                        true
                    } else false
                }
                "markTerminal" -> {
                    assertTrue(inTransaction())
                    writes += name
                    terminalAt = args[3] as Instant
                    false // Isolate the lease/deadline decision; no aggregate transition follows a rejected CAS.
                }
                "schedulePoll" -> { assertTrue(inTransaction()); writes += name; true }
                "restartAfterJobFailure" -> {
                    assertTrue(inTransaction())
                    writes += name
                    jobRetry = args[0] as NormalizationJobRetry
                    true
                }
                else -> error("Unexpected runtime.$name")
            }
        }
        private val grants = port<ArtifactAccessGrantIssuer> { name, args ->
            assertEquals("issueReadGrant", name)
            assertFalse(inTransaction())
            externalCalls += name
            val artifact = args[0] as NormalizationArtifactInput
            ArtifactReadGrant(artifact.artifactId.value.toString(), artifact.role.name, artifact.sha256,
                artifact.contentLength, artifact.mediaType, "https://grant.test/synthetic", start.plusSeconds(300))
        }
        private val compute = port<TradingNormalizationComputeClient> { name, args ->
            assertFalse(inTransaction())
            when (name) {
                "status" -> NormalizationStatusLookup.Found(NormalizationJobStatus("job-1",
                    if (terminalCode == null) "RUNNING" else "FAILED", token, 0, 0, "a".repeat(64),
                    "f".repeat(64), action, start.plusSeconds(deadlineSeconds), terminalCode, resultVersionPin = pin))
                "updateGrants" -> {
                    externalCalls += name
                    sentToken = args[3] as String
                    sentUpdateKey = args[5] as String
                    NormalizationGrantUpdate.Accepted
                }
                else -> error("Unexpected compute.$name")
            }
        }
        private val transactions = TransactionTemplate(object : AbstractPlatformTransactionManager() {
            override fun doGetTransaction(): Any = Any()
            override fun doBegin(transaction: Any, definition: TransactionDefinition) {}
            override fun doCommit(status: DefaultTransactionStatus) {}
            override fun doRollback(status: DefaultTransactionStatus) {}
        })
        val generated = mutableListOf<String>()
        val nextToken = "00000000-0000-0000-0000-000000000004"
        val nextDispatchId = "00000000-0000-0000-0000-000000000005"
        val updateKey = "00000000-0000-0000-0000-000000000006"
        private val identifiers = port<TradingNormalizationIdGenerator> { name, _ ->
            generated += name
            when (name) {
                "attemptToken" -> nextToken
                "dispatchId" -> nextDispatchId
                "grantUpdateKey" -> updateKey
                else -> error("Worker must reuse its Run identity: $name")
            }
        }
        private val mapper = JsonMapper.builder().build()
        val service = PollTradingNormalizationService(runtime, sessions, grants, compute,
            NormalizationRetrySchedule(mapper, object : TradingRetryJitter { override fun factor() = 0.5 }),
            NormalizationWorkerLifecycle(runtime, sessions, books, artifacts), transactions, clock, mapper, identifiers)
    }

    private class MutableClock(var value: Instant) : Clock() {
        override fun instant() = value
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }

    private companion object {
        fun inTransaction() = TransactionSynchronizationManager.isActualTransactionActive()
        inline fun <reified T> port(noinline call: (String, Array<out Any?>) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
                call(method.name.substringBefore('-'), args ?: emptyArray())
            } as T
    }
}
