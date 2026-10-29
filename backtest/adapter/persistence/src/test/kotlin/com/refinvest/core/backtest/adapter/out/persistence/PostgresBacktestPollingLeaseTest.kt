package com.refinvest.core.backtest.adapter.out.persistence

import com.refinvest.core.backtest.application.dispatch.PollSubmittedBacktestsService
import com.refinvest.core.backtest.application.dispatch.DispatchPendingBacktestsService
import com.refinvest.core.backtest.adapter.out.compute.RestClientComputeClient
import com.refinvest.core.backtest.adapter.out.compute.RestClientComputeBacktestStatusClient
import com.refinvest.core.strategy.port.inbound.version.backtest.*
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import com.refinvest.core.backtest.application.execution.RecordBacktestRunExecutionService
import com.refinvest.core.backtest.domain.BacktestRun
import com.refinvest.core.backtest.domain.backtest.*
import com.refinvest.core.backtest.domain.valueobject.*
import com.refinvest.core.backtest.domain.valueobject.Period
import com.refinvest.core.backtest.port.inbound.execution.CompleteBacktestRunCommand
import com.refinvest.core.backtest.port.inbound.execution.RecordBacktestRunExecutionUseCase
import com.refinvest.core.backtest.port.outbound.compute.*
import com.refinvest.core.backtest.port.outbound.observability.BacktestFailureObserver
import com.refinvest.core.backtest.port.outbound.observability.ComputeBacktestFailureObservation
import com.refinvest.core.backtest.port.outbound.persistence.BacktestQuotaRequest
import com.refinvest.core.backtest.port.outbound.persistence.dispatch.PendingBacktestComputeDispatch
import com.refinvest.core.shared.kernel.member.MemberId
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import org.springframework.jdbc.core.JdbcTemplate
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.sql.DriverManager
import java.time.*
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@EnabledIfEnvironmentVariable(named = "BACKTEST_TEST_JDBC_URL", matches = ".+")
class PostgresBacktestPollingLeaseTest {
    @Test
    fun actualSubmitBodyTimeoutRetriesThePersistedKeyWithoutReservingAgain() = withFixture(submitted = false) { f ->
        withDelayedResponse(202, """{"runId":"synthetic-compute-job","status":"PENDING"}""") { server, release, requests ->
            val client = RestClientComputeClient("http://127.0.0.1:${server.address.port}", "synthetic-key", f.registry,
                Duration.ofSeconds(1), Duration.ofMillis(300))
            val submission = f.submit(client)
            assertTrue(submission.execute())
            assertEquals("PENDING", f.jdbc.queryForObject("SELECT status FROM backtest_compute_dispatches", String::class.java))
            assertEquals(f.clock.instant().plusSeconds(10), f.jdbc.queryForObject(
                "SELECT next_attempt_at FROM backtest_compute_dispatches", java.sql.Timestamp::class.java)?.toInstant())
            assertEquals(1L, f.jdbc.queryForObject("SELECT count(*) FROM backtest_capacity_reservations", Long::class.java))
            release.countDown()
            f.clock.advance(Duration.ofSeconds(10))
            assertTrue(submission.execute())
            assertEquals(2, requests.size)
            assertEquals(requests[0], requests[1], "Both the persisted idempotency key and request must be reused")
            assertEquals("SUBMITTED", f.jdbc.queryForObject("SELECT status FROM backtest_compute_dispatches", String::class.java))
            assertEquals("synthetic-compute-job", f.jdbc.queryForObject("SELECT compute_run_id FROM backtest_compute_dispatches", String::class.java))
            assertEquals(1, f.jdbc.queryForObject("SELECT used_count FROM backtest_quotas", Int::class.java))
            assertEquals(1L, f.jdbc.queryForObject("SELECT count(*) FROM backtest_capacity_reservations", Long::class.java))
        }
    }

    @Test
    fun permanentSubmissionRejectionReloadsTheRunAndCommitsCapacityRelease() = withFixture(submitted = false) { f ->
        val submission = f.submit(ComputeClient { ComputeBacktestSubmission.Rejected("synthetic rejection", "DSL_INVALID") })
        assertTrue(submission.execute())
        val restored = f.tx.execute { f.runs.findById(BacktestRunId(1)) }!!
        assertEquals(BacktestRunStatus.FAILED, restored.status)
        assertNull(restored.actualPeriod)
        assertEquals("DSL_INVALID", restored.errorCode)
        assertEquals(0L, f.jdbc.queryForObject("SELECT count(*) FROM backtest_capacity_reservations", Long::class.java))
        assertEquals(1, f.jdbc.queryForObject("SELECT used_count FROM backtest_quotas", Int::class.java))
        assertEquals("REJECTED", f.jdbc.queryForObject("SELECT status FROM backtest_compute_dispatches", String::class.java))
        assertEquals(1, f.observations.size)
    }

    @Test
    fun actualPollBodyTimeoutKeepsRunAndCapacityUntilTerminalCompletion() = withFixture { f ->
        withDelayedResponse(200, """{"status":"PENDING"}""") { server, release, _ ->
            val client = RestClientComputeBacktestStatusClient("http://127.0.0.1:${server.address.port}", "synthetic-key",
                jacksonObjectMapper(), f.registry, Duration.ofSeconds(1), Duration.ofMillis(300))
            val polling = f.poll { client.getBacktestStatus("synthetic-compute-job", BacktestRunId(1)) }
            assertTrue(polling.execute())
            assertEquals("PENDING", f.jdbc.queryForObject("SELECT status FROM backtest_runs", String::class.java))
            assertEquals("SUBMITTED", f.jdbc.queryForObject("SELECT status FROM backtest_compute_dispatches", String::class.java))
            assertEquals(f.clock.instant().plusSeconds(10), f.jdbc.queryForObject(
                "SELECT next_attempt_at FROM backtest_compute_dispatches", java.sql.Timestamp::class.java)?.toInstant())
            assertEquals(0L, f.jdbc.queryForObject("SELECT count(*) FROM backtest_results", Long::class.java))
            assertEquals(1L, f.jdbc.queryForObject("SELECT count(*) FROM backtest_capacity_reservations", Long::class.java))
            release.countDown()
            f.clock.advance(Duration.ofSeconds(10))
            assertTrue(polling.execute())
            f.clock.advance(Duration.ofSeconds(5))
            assertTrue(f.poll { response(2) }.execute())
            assertEquals("COMPLETED", f.jdbc.queryForObject("SELECT status FROM backtest_runs", String::class.java))
            assertEquals(0L, f.jdbc.queryForObject("SELECT count(*) FROM backtest_capacity_reservations", Long::class.java))
            assertEquals(1, f.jdbc.queryForObject("SELECT used_count FROM backtest_quotas", Int::class.java))
        }
    }

    @Test
    fun responseFromReplacedLeaseCannotFailRunOrReleaseCapacity() = withFixture { f ->
        val old = f.poll {
            f.clock.advance(Duration.ofSeconds(61))
            val replacement = assertNotNull(f.tx.execute {
                f.dispatches.claimNextSubmitted(f.clock.instant(), Duration.ofMinutes(1))
            })
            f.replacementToken = replacement.claimToken
            ComputeBacktestStatusLookup.NotFound
        }
        assertTrue(old.execute())
        assertEquals("PENDING", f.jdbc.queryForObject("SELECT status FROM backtest_runs WHERE id=1", String::class.java))
        assertEquals(1L, f.jdbc.queryForObject("SELECT count(*) FROM backtest_capacity_reservations", Long::class.java))
        assertEquals(f.replacementToken, f.jdbc.queryForObject(
            "SELECT lease_token FROM backtest_compute_dispatches WHERE backtest_run_id=1", UUID::class.java))
        assertEquals(0, f.observations.size)
    }

    @Test
    fun everyExpiredPollingResponseLeavesPersistentStateUntouched() {
        for (mode in 0..6) withFixture { f ->
            var before: Any? = null
            assertTrue(f.poll {
                f.clock.advance(Duration.ofMinutes(1)) // Equality with the deadline is expired.
                before = f.snapshot()
                response(mode)
            }.execute())
            assertEquals(before, f.snapshot(), "response mode $mode")
            assertEquals(0, f.observations.size)
        }
    }

    @Test
    fun everyReplacedPollingResponseLeavesNewOwnerStateUntouched() {
        for (mode in 0..6) withFixture { f ->
            var before: Any? = null
            assertTrue(f.poll {
                f.clock.advance(Duration.ofSeconds(61))
                assertNotNull(f.tx.execute { f.dispatches.claimNextSubmitted(f.clock.instant(), Duration.ofMinutes(1)) })
                before = f.snapshot()
                response(mode)
            }.execute())
            assertEquals(before, f.snapshot(), "response mode $mode")
            assertEquals(0, f.observations.size)
        }
    }

    @Test
    fun currentClaimRequiresMatchingJobAndAnEnclosingTransaction() = withFixture { f ->
        val claim = assertNotNull(f.tx.execute { f.dispatches.claimNextSubmitted(f.clock.instant(), Duration.ofMinutes(1)) })
        assertEquals(true, f.tx.execute { f.dispatches.lockSubmittedClaim(claim) })
        assertEquals(false, f.tx.execute { f.dispatches.lockSubmittedClaim(claim.copy(computeRunId = "another-job")) })
        assertEquals(false, f.tx.execute { f.dispatches.lockSubmittedClaim(claim.copy(claimToken = UUID.randomUUID())) })
        assertEquals(false, f.tx.execute { f.dispatches.lockSubmittedClaim(claim.copy(backtestRunId = BacktestRunId(999))) })
        assertFailsWith<IllegalStateException> { f.dispatches.lockSubmittedClaim(claim) }
    }

    @Test
    fun validTerminalResponsesCommitRunResultCapacityAndDispatchTogether() {
        for (mode in listOf(2, 3, 4, 6)) withFixture { f ->
            assertTrue(f.poll { response(mode) }.execute())
            assertEquals(if (mode == 2) "COMPLETED" else "FAILED",
                f.jdbc.queryForObject("SELECT status FROM backtest_runs WHERE id=1", String::class.java))
            assertEquals("TERMINAL", f.jdbc.queryForObject("SELECT status FROM backtest_compute_dispatches WHERE backtest_run_id=1", String::class.java))
            assertEquals(0L, f.jdbc.queryForObject("SELECT count(*) FROM backtest_capacity_reservations", Long::class.java))
            assertEquals(if (mode == 2) 1L else 0L, f.jdbc.queryForObject("SELECT count(*) FROM backtest_results", Long::class.java))
            assertEquals(1, f.jdbc.queryForObject("SELECT used_count FROM backtest_quotas", Int::class.java))
            assertEquals(if (mode == 3 || mode == 6) 1 else 0, f.observations.size)
        }
    }

    @Test
    fun delayedOldWorkerCannotOverwriteFreshCompletion() = withFixture { f ->
        val inHttp = CountDownLatch(1)
        val releaseHttp = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val old = pool.submit<Boolean> { f.poll {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
                inHttp.countDown()
                check(releaseHttp.await(15, TimeUnit.SECONDS))
                ComputeBacktestStatusLookup.NotFound
            }.execute() }
            assertTrue(inHttp.await(15, TimeUnit.SECONDS))
            f.clock.advance(Duration.ofSeconds(61))
            assertTrue(f.poll { response(2) }.execute())
            val completed = f.snapshot()
            releaseHttp.countDown()
            assertTrue(old.get(15, TimeUnit.SECONDS))
            assertEquals(completed, f.snapshot())
            assertEquals(0, f.observations.size)
        } finally { releaseHttp.countDown(); pool.shutdownNow() }
    }

    @Test
    fun responseLockProtectsCommitEvenWhenLeaseExpiresDuringProcessing() = withFixture { f ->
        val applying = CountDownLatch(1)
        val commit = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val processing = pool.submit<Boolean> {
                f.poll({ response(2) }, RecordBacktestRunExecutionUseCase { command ->
                    val id = f.execution.execute(command)
                    if (command is CompleteBacktestRunCommand) {
                        applying.countDown()
                        check(commit.await(15, TimeUnit.SECONDS))
                    }
                    id
                }).execute()
            }
            assertTrue(applying.await(15, TimeUnit.SECONDS))
            f.clock.advance(Duration.ofSeconds(61))
            val reclaimer = pool.submit<Any?> {
                f.tx.execute { f.dispatches.claimNextSubmitted(f.clock.instant(), Duration.ofMinutes(1)) }
            }
            f.awaitLockWaiter()
            assertFailsWith<TimeoutException> { reclaimer.get(100, TimeUnit.MILLISECONDS) }
            commit.countDown()
            assertTrue(processing.get(15, TimeUnit.SECONDS))
            assertNull(reclaimer.get(15, TimeUnit.SECONDS))
            assertEquals("COMPLETED", f.jdbc.queryForObject("SELECT status FROM backtest_runs WHERE id=1", String::class.java))
            assertEquals(0L, f.jdbc.queryForObject("SELECT count(*) FROM backtest_capacity_reservations", Long::class.java))
        } finally { commit.countDown(); pool.shutdownNow() }
    }

    @Test
    fun leaseExpiryIsCheckedAfterWaitingForDispatchLock() = withFixture { f ->
        val inHttp = CountDownLatch(1)
        val returnHttp = CountDownLatch(1)
        val locked = CountDownLatch(1)
        val unlock = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val polling = pool.submit<Boolean> { f.poll {
                inHttp.countDown()
                check(returnHttp.await(15, TimeUnit.SECONDS))
                ComputeBacktestStatusLookup.NotFound
            }.execute() }
            assertTrue(inHttp.await(15, TimeUnit.SECONDS))
            val before = f.snapshot()
            val blocker = pool.submit { f.tx.execute {
                assertNotNull(f.dispatchRepository.findByIdForUpdate(1))
                locked.countDown()
                check(unlock.await(15, TimeUnit.SECONDS))
            } }
            assertTrue(locked.await(15, TimeUnit.SECONDS))
            returnHttp.countDown()
            f.awaitLockWaiter()
            f.clock.advance(Duration.ofMinutes(1))
            unlock.countDown()
            blocker.get(15, TimeUnit.SECONDS)
            assertTrue(polling.get(15, TimeUnit.SECONDS))
            assertEquals(before, f.snapshot())
            assertEquals(0, f.observations.size)
        } finally { returnHttp.countDown(); unlock.countDown(); pool.shutdownNow() }
    }

    @Test
    fun publicationFailureRollsBackRunResultCapacityAndDispatch() = withFixture { f ->
        var before: Any? = null
        val service = f.poll({ before = f.snapshot(); response(2) }, RecordBacktestRunExecutionUseCase { command ->
            val id = f.execution.execute(command)
            if (command is CompleteBacktestRunCommand) error("Synthetic failure after completion and capacity release")
            id
        })
        assertFailsWith<IllegalStateException> { service.execute() }
        assertEquals(before, f.snapshot())
        assertEquals(0, f.observations.size)
    }

    private fun response(mode: Int): ComputeBacktestStatusLookup {
        val period = Period(LocalDate.parse("2025-01-01"), LocalDate.parse("2025-01-03"))
        val snapshot = DatasetSnapshotId("synthetic-snapshot")
        val engine = EngineVersion("synthetic-engine")
        val result = BacktestResult(BacktestRunId(1),
            BacktestResultMetrics(null, null, null, null, null, 0, null, null, null),
            emptyList(), emptyList(),
            Benchmark(BuyAndHoldResult("QQQ", emptyList(), BigDecimal.ZERO, null, BigDecimal.ZERO), null),
            SignalExecutionMarketRelation.SAME_MARKET, SignalExecutionDelay(BigDecimal.ZERO, BigDecimal.ZERO, emptyList()),
            SampleSizeWarning.ZERO, DataIntegrityStatus(snapshot, true, true))
        return when (mode) {
            0 -> ComputeBacktestStatusLookup.Found(ComputeBacktestStatus(ComputeBacktestStatusValue.PENDING))
            1 -> ComputeBacktestStatusLookup.Found(ComputeBacktestStatus(ComputeBacktestStatusValue.RUNNING, period, snapshot, engine))
            2 -> ComputeBacktestStatusLookup.Found(ComputeBacktestStatus(ComputeBacktestStatusValue.COMPLETED, period, snapshot, engine, result))
            3 -> ComputeBacktestStatusLookup.Found(ComputeBacktestStatus(ComputeBacktestStatusValue.FAILED, period, snapshot, engine,
                failureReason = "Synthetic failure", errorCode = "SYNTHETIC_FAILURE"))
            4 -> ComputeBacktestStatusLookup.NotFound
            5 -> ComputeBacktestStatusLookup.RetryLater(Duration.ofSeconds(30))
            else -> ComputeBacktestStatusLookup.Found(ComputeBacktestStatus(ComputeBacktestStatusValue.FAILED,
                failureReason = "Synthetic pre-execution failure"))
        }
    }

    private class MutableClock : Clock() {
        private val time = AtomicReference(Instant.parse("2026-10-01T00:00:00Z"))
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = time.get()
        fun advance(duration: Duration) { time.updateAndGet { it.plus(duration) } }
    }

    private class Fixture(source: DataSource, factory: jakarta.persistence.EntityManagerFactory,
                          private val applicationName: String, submitted: Boolean) {
        val clock = MutableClock()
        val jdbc = JdbcTemplate(source)
        val tx = TransactionTemplate(JpaTransactionManager(factory).apply { dataSource = source })
        val registry = SimpleMeterRegistry()
        private val entityManager = SharedEntityManagerCreator.createSharedEntityManager(factory)
        private val repositories = JpaRepositoryFactory(entityManager)
        val dispatchRepository = repositories.getRepository(BacktestComputeDispatchJpaStore::class.java)
        val dispatches = JpaBacktestComputeDispatchStoreAdapter(dispatchRepository, registry, clock)
        val runs = JpaBacktestRunStoreAdapter(repositories.getRepository(BacktestRunJpaStore::class.java),
            repositories.getRepository(BacktestResultJpaStore::class.java),
            BacktestResultPayloadMapper(jacksonObjectMapper()), registry, entityManager)
        private val quota = JdbcBacktestQuotaStoreAdapter(jdbc)
        val execution = RecordBacktestRunExecutionService(runs, quota)
        val observations = CopyOnWriteArrayList<ComputeBacktestFailureObservation>()
        var replacementToken: UUID? = null

        init {
            tx.execute {
                quota.reserve(BacktestQuotaRequest(MemberId(99), BacktestRunId(1), YearMonth.of(2026, 10), 30, 1))
                runs.save(BacktestRun.createPending(BacktestRunId(1), StrategyId(11), StrategyVersionId(12),
                    Period(LocalDate.parse("2025-01-01"), LocalDate.parse("2025-01-03")),
                    FeeModel(Percent(BigDecimal.ZERO), Percent(BigDecimal.ZERO)), clock.instant()))
                dispatches.enqueue(PendingBacktestComputeDispatch(BacktestRunId(1), ComputeIdempotencyKey(UUID.randomUUID()), clock.instant()))
                if (submitted) {
                    val claim = assertNotNull(dispatches.claimNext(clock.instant(), Duration.ofMinutes(1)))
                    dispatches.markAccepted(claim.backtestRunId, claim.claimToken, "synthetic-compute-job")
                }
            }
        }

        fun snapshot(): Any = listOf(
            jdbc.queryForMap("SELECT status,actual_period_start,actual_period_end,dataset_snapshot_id,engine_version,failure_reason,error_code FROM backtest_runs WHERE id=1"),
            jdbc.queryForMap("SELECT status,lease_token,lease_expires_at,next_attempt_at,poll_retry_pending,updated_at FROM backtest_compute_dispatches WHERE backtest_run_id=1"),
            jdbc.queryForList("SELECT backtest_run_id,member_id,quota_month FROM backtest_capacity_reservations ORDER BY backtest_run_id"),
            jdbc.queryForObject("SELECT count(*) FROM backtest_results", Long::class.java),
            jdbc.queryForObject("SELECT used_count FROM backtest_quotas", Int::class.java))

        fun awaitLockWaiter() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (System.nanoTime() < deadline) {
                if (jdbc.queryForObject("""SELECT EXISTS(SELECT 1 FROM pg_stat_activity
                    WHERE application_name=? AND wait_event_type='Lock'
                    AND query ILIKE '%backtest_compute_dispatches%')""", Boolean::class.java, applicationName) == true) return
                Thread.sleep(10)
            }
            error("Expected a PostgreSQL dispatch lock waiter")
        }

        fun poll(lookup: () -> ComputeBacktestStatusLookup) = poll(lookup, execution)

        fun submit(client: ComputeClient) = DispatchPendingBacktestsService(dispatches, runs, quota,
            LookupStrategyVersionForBacktestUseCase {
                LookupStrategyVersionForBacktestResult(11, MemberId(99), setOf("QQQ"), StrategyVersionForBacktest(
                    "QQQ", listOf(StrategyConditionForBacktest("GT", null,
                        StrategyMetricReferenceForBacktest("QQQ", "SIMPLE", null), StrategyLiteralOperandForBacktest(0.0))),
                    "QQQ", 0, 1))
            }, ComputeClient { request ->
                check(!TransactionSynchronizationManager.isActualTransactionActive()) { "HTTP must run outside a DB transaction" }
                client.requestBacktest(request)
            }, object : BacktestFailureObserver {
                override fun recordComputeFailure(observation: ComputeBacktestFailureObservation) { observations += observation }
            }, tx, clock)

        fun poll(lookup: () -> ComputeBacktestStatusLookup,
                 executionUseCase: RecordBacktestRunExecutionUseCase) = PollSubmittedBacktestsService(dispatches, runs,
            executionUseCase, ComputeBacktestStatusClient { _, _ ->
                check(!TransactionSynchronizationManager.isActualTransactionActive()) { "HTTP must run outside a DB transaction" }
                lookup()
            },
            object : BacktestFailureObserver {
                override fun recordComputeFailure(observation: ComputeBacktestFailureObservation) { observations += observation }
            }, tx, clock)
    }

    private fun withFixture(submitted: Boolean = true, test: (Fixture) -> Unit) {
        val url = requireNotNull(System.getenv("BACKTEST_TEST_JDBC_URL"))
        val user = requireNotNull(System.getenv("BACKTEST_TEST_DB_USER"))
        val password = requireNotNull(System.getenv("BACKTEST_TEST_DB_PASSWORD"))
        val schema = "backtest_lease_test_" + UUID.randomUUID().toString().replace("-", "")
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
            val bean = LocalContainerEntityManagerFactoryBean()
            val source = HikariDataSource().apply {
                jdbcUrl = url
                username = user
                this.password = password
                poolName = schema
                maximumPoolSize = 4
                minimumIdle = 0
                connectionTimeout = 5000
                initializationFailTimeout = 5000
                addDataSourceProperty("currentSchema", schema)
                addDataSourceProperty("ApplicationName", schema)
            }
            try {
                bean.dataSource = source
                bean.jpaVendorAdapter = HibernateJpaVendorAdapter()
                bean.setPackagesToScan("com.refinvest.core.backtest.adapter.out.persistence")
                bean.setJpaPropertyMap(mapOf("hibernate.hbm2ddl.auto" to "create", "hibernate.jdbc.time_zone" to "UTC"))
                bean.afterPropertiesSet()
                test(Fixture(source, requireNotNull(bean.`object`), schema, submitted))
            } finally {
                bean.destroy()
                source.close()
                connection.createStatement().use { statement ->
                    // PostgreSQL large objects referenced by @Lob are not removed by DROP SCHEMA.
                    val hasLob = statement.executeQuery("""SELECT EXISTS(SELECT 1 FROM information_schema.columns
                        WHERE table_schema='$schema' AND table_name='backtest_results'
                        AND column_name='result_payload' AND udt_name='oid')""").use { it.next(); it.getBoolean(1) }
                    if (hasLob) statement.execute("SELECT lo_unlink(result_payload) FROM $schema.backtest_results WHERE result_payload IS NOT NULL")
                    statement.execute("DROP SCHEMA $schema CASCADE")
                }
            }
        }
    }

    private fun withDelayedResponse(status: Int, body: String,
                                    test: (HttpServer, CountDownLatch, CopyOnWriteArrayList<Pair<String, String>>) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val release = CountDownLatch(1)
        val requests = CopyOnWriteArrayList<Pair<String, String>>()
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                check(!TransactionSynchronizationManager.isActualTransactionActive())
                requests += (exchange.requestHeaders.getFirst("Idempotency-Key") ?: "") to exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
                val bytes = body.toByteArray()
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                if (requests.size == 1) {
                    exchange.responseBody.write(bytes, 0, 1)
                    exchange.responseBody.flush()
                    release.await(10, TimeUnit.SECONDS)
                } else exchange.responseBody.write(bytes)
            } finally { exchange.close() }
        }
        server.start()
        try { test(server, release, requests) }
        finally { release.countDown(); server.stop(0); executor.shutdownNow() }
    }
}
