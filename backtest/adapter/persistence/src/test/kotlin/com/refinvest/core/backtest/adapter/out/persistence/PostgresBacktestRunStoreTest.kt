package com.refinvest.core.backtest.adapter.out.persistence

import com.refinvest.core.backtest.application.execution.RecordBacktestRunExecutionService
import com.refinvest.core.backtest.domain.BacktestRun
import com.refinvest.core.backtest.domain.valueobject.*
import com.refinvest.core.backtest.port.inbound.execution.CompleteBacktestRunCommand
import com.refinvest.core.backtest.port.inbound.execution.StartBacktestRunCommand
import com.refinvest.core.backtest.port.outbound.persistence.BacktestQuotaRequest
import com.refinvest.core.shared.kernel.member.MemberId
import com.zaxxer.hikari.HikariDataSource
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.persistence.EntityManagerFactory
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.sql.DriverManager
import java.time.YearMonth
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

@EnabledIfEnvironmentVariable(named = "BACKTEST_TEST_JDBC_URL", matches = ".+")
class PostgresBacktestRunStoreTest {
    @Test
    fun staleRunCannotOverwriteACommittedTerminalOutcome() = withFixture { f ->
        f.start()
        val stale = f.load()
        f.complete()
        val committed = f.snapshot()
        assertFailsWith<IllegalArgumentException> { f.save(stale) }
        assertEquals(committed, f.snapshot())
        assertEquals(BacktestRunStatus.COMPLETED, f.load().status)
        assertEquals(0, f.reservations())
    }

    @Test
    fun terminalReplayPreservesThePayloadOidAndCountsCompletionOnlyOnce() = withFixture { f ->
        f.start(); f.complete()
        val committed = f.snapshot()
        val completed = f.load()
        repeat(2) { f.save(completed) }
        assertEquals(committed, f.snapshot())
        assertEquals(1.0, f.registry.get("refinvest.backtest.terminal").tag("status", "COMPLETED").counter().count())
        assertEquals(0, f.reservations())
        assertEquals(1, f.jdbc.queryForObject("SELECT used_count FROM backtest_quotas", Int::class.java))
    }

    @Test
    fun completedPayloadAndPinnedExecutionMetadataCannotBeReplaced() = withFixture { f ->
        f.start()
        val running = f.load()
        val changedMetadata = BacktestRun.restore(running.id, running.strategyId, running.strategyVersionId, running.requestedPeriod,
            running.feeModel, running.createdAt, BacktestRunStatus.RUNNING, running.actualPeriod, DatasetSnapshotId("changed"),
            running.engineVersion, null, null)
        assertFailsWith<IllegalArgumentException> { f.save(changedMetadata) }
        f.complete()
        val committed = f.snapshot()
        val completed = f.load()
        val changedResult = BacktestRun.restore(completed.id, completed.strategyId, completed.strategyVersionId, completed.requestedPeriod,
            completed.feeModel, completed.createdAt, BacktestRunStatus.COMPLETED, completed.actualPeriod, completed.datasetSnapshotId,
            completed.engineVersion, BacktestMappingFixtures.result(amount = "0.20"), null)
        assertFailsWith<IllegalArgumentException> { f.save(changedResult) }
        assertEquals(committed, f.snapshot())
    }

    @Test
    fun failedOutcomeCannotAcquireExecutionMetadataOrChangeItsReason() = withFixture { f ->
        val failed = f.load().apply { failWithoutExecution("not accepted") }
        f.save(failed)
        val committed = f.snapshot()
        val changed = BacktestRun.restore(failed.id, failed.strategyId, failed.strategyVersionId, failed.requestedPeriod,
            failed.feeModel, failed.createdAt, BacktestRunStatus.FAILED, BacktestMappingFixtures.period, BacktestMappingFixtures.snapshot,
            BacktestMappingFixtures.engine, null, "not accepted")
        assertFailsWith<IllegalArgumentException> { f.save(changed) }
        val changedReason = f.load().let { BacktestRun.restore(it.id, it.strategyId, it.strategyVersionId, it.requestedPeriod,
            it.feeModel, it.createdAt, it.status, null, null, null, null, "another reason") }
        assertFailsWith<IllegalArgumentException> { f.save(changedReason) }
        assertEquals(committed, f.snapshot())
    }

    @Test
    fun rollbackKeepsTheRunResultCapacityAndMonthlyUsageAtomic() = withFixture { f ->
        f.start()
        val before = f.snapshot()
        assertFailsWith<IllegalStateException> {
            f.tx.execute { f.complete(); f.flush(); error("Synthetic failure after result persistence and capacity release") }
        }
        assertEquals(before, f.snapshot())
        assertEquals(BacktestRunStatus.RUNNING, f.load().status)
        assertEquals(1, f.reservations())
        assertEquals(0L, f.jdbc.queryForObject("SELECT count(*) FROM backtest_results", Long::class.java))
        assertNull(f.registry.find("refinvest.backtest.terminal").counter())
        f.complete()
        assertEquals(0, f.reservations())
        assertEquals(1, f.jdbc.queryForObject("SELECT used_count FROM backtest_quotas", Int::class.java))
    }

    @Test
    fun waitingCommandReloadsAnEarlierReadAfterTheFirstWriterCommits() = withFixture { f ->
        f.start()
        val written = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val first = workers.submit {
                f.tx.execute { f.complete(); f.flush(); written.countDown(); check(release.await(15, TimeUnit.SECONDS)) }
            }
            assertTrue(written.await(15, TimeUnit.SECONDS))
            val second = workers.submit<BacktestRunStatus> {
                f.tx.execute {
                    assertEquals(BacktestRunStatus.RUNNING, f.reader.findById(BacktestRunId(1))?.status)
                    f.store.findById(BacktestRunId(1))!!.status
                }
            }
            f.awaitLockWaiter()
            release.countDown()
            first.get(15, TimeUnit.SECONDS)
            assertEquals(BacktestRunStatus.COMPLETED, second.get(15, TimeUnit.SECONDS))
            assertEquals(0, f.reservations())
        } finally {
            release.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS))
        }
    }

    @Test
    fun unrelatedRunsDoNotShareTheAggregateLock() = withFixture { f ->
        f.save(BacktestMappingFixtures.pending(2))
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workers = Executors.newSingleThreadExecutor()
        try {
            val first = workers.submit {
                f.tx.execute { f.store.findById(BacktestRunId(1)); locked.countDown(); check(release.await(15, TimeUnit.SECONDS)) }
            }
            assertTrue(locked.await(15, TimeUnit.SECONDS))
            val other = f.load(2).apply { start(BacktestMappingFixtures.period, BacktestMappingFixtures.snapshot, BacktestMappingFixtures.engine) }
            f.save(other)
            assertEquals(BacktestRunStatus.RUNNING, f.load(2).status)
            release.countDown(); first.get(15, TimeUnit.SECONDS)
        } finally {
            release.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS))
        }
    }

    @Test
    fun everyValidStateAndNullableResultSurvivesPostgresPersistence() = withFixture { f ->
        val runs = listOf(BacktestMappingFixtures.pending(2), BacktestMappingFixtures.running(3),
            BacktestMappingFixtures.completed(4), BacktestMappingFixtures.completed(5, zero = true),
            BacktestMappingFixtures.pending(6).apply { failWithoutExecution("not accepted") },
            BacktestMappingFixtures.running(7).apply { fail("missing prices", "PRICE_DATA_MISSING") })
        runs.forEach { original ->
            f.save(original)
            val restored = f.load(original.id.value)
            assertEquals(original.status, restored.status)
            assertEquals(original.failureReason, restored.failureReason)
            assertEquals(original.errorCode, restored.errorCode)
            assertEquals(original.actualPeriod, restored.actualPeriod)
            assertEquals(original.datasetSnapshotId, restored.datasetSnapshotId)
            assertEquals(original.engineVersion, restored.engineVersion)
            val before = f.snapshot()
            f.save(original) // The original nanosecond Clock and fee scale are accepted at stored precision.
            assertEquals(before, f.snapshot())
            val read = f.reader.findById(original.id)!!
            assertEquals(original.status, read.status)
            if (original.result != null) {
                val result = f.tx.execute { f.resultReader.findByBacktestRunId(original.id) }!!
                assertEquals(original.result!!.metrics, result.metrics)
                assertEquals(original.result!!.trades, result.trades)
                assertEquals(original.result!!.benchmark, result.benchmark)
                assertEquals(original.result!!.dataIntegrityStatus, result.dataIntegrityStatus)
            } else assertNull(restored.result)
        }
    }

    @Test
    fun immutableRunInputsAndCommandTransactionBoundariesAreEnforced() = withFixture { f ->
        val original = f.load()
        val changed = BacktestRun.createPending(original.id, StrategyId(999), original.strategyVersionId,
            original.requestedPeriod, original.feeModel, original.createdAt)
        val before = f.snapshot()
        assertFailsWith<IllegalArgumentException> { f.save(changed) }
        assertEquals(before, f.snapshot())
        assertFailsWith<IllegalStateException> { f.store.findById(BacktestRunId(1)) }
    }

    private class Fixture(source: HikariDataSource, factory: EntityManagerFactory, private val name: String) {
        val jdbc = JdbcTemplate(source)
        val tx = TransactionTemplate(JpaTransactionManager(factory).apply { dataSource = source })
        private val entityManager = SharedEntityManagerCreator.createSharedEntityManager(factory)
        private val repositories = JpaRepositoryFactory(entityManager)
        val registry = SimpleMeterRegistry()
        val payload = BacktestResultPayloadMapper(jacksonObjectMapper())
        val store = JpaBacktestRunStoreAdapter(repositories.getRepository(BacktestRunJpaStore::class.java),
            repositories.getRepository(BacktestResultJpaStore::class.java), payload, registry, entityManager)
        val reader = JpaBacktestRunReaderAdapter(repositories.getRepository(BacktestRunJpaReader::class.java))
        val resultReader = JpaBacktestResultReaderAdapter(repositories.getRepository(BacktestResultJpaReader::class.java), payload)
        private val quota = JdbcBacktestQuotaStoreAdapter(jdbc)
        private val execution = RecordBacktestRunExecutionService(store, quota)
        init {
            tx.execute {
                quota.reserve(BacktestQuotaRequest(MemberId(99), BacktestRunId(1), YearMonth.of(2026,10), 30, 10))
                store.save(BacktestMappingFixtures.pending())
            }
        }
        fun save(run: BacktestRun) { tx.execute { store.save(run) } }
        fun load(id: Long = 1): BacktestRun = tx.execute { store.findById(BacktestRunId(id)) }!!
        fun start() { tx.execute { execution.execute(StartBacktestRunCommand(BacktestRunId(1), BacktestMappingFixtures.period,
            BacktestMappingFixtures.snapshot, BacktestMappingFixtures.engine)) } }
        fun complete() { tx.execute { execution.execute(CompleteBacktestRunCommand(BacktestRunId(1), BacktestMappingFixtures.result())) } }
        fun flush() = entityManager.flush()
        fun reservations(): Int = jdbc.queryForObject("SELECT count(*) FROM backtest_capacity_reservations", Int::class.java)!!
        fun snapshot(): Any = listOf(jdbc.queryForList("SELECT * FROM backtest_runs ORDER BY id"),
            jdbc.queryForList("SELECT backtest_run_id,result_payload::text FROM backtest_results ORDER BY backtest_run_id"),
            jdbc.queryForList("SELECT * FROM backtest_capacity_reservations ORDER BY backtest_run_id"),
            jdbc.queryForList("SELECT * FROM backtest_quotas ORDER BY member_id"))
        fun awaitLockWaiter() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (System.nanoTime() < deadline) {
                if (jdbc.queryForObject("""SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE application_name=?
                    AND wait_event_type='Lock' AND query ILIKE '%backtest_runs%')""", Boolean::class.java, name) == true) return
                Thread.sleep(10)
            }
            error("Expected a PostgreSQL Run lock waiter")
        }
    }

    private fun withFixture(test: (Fixture) -> Unit) {
        val url = requireNotNull(System.getenv("BACKTEST_TEST_JDBC_URL"))
        val user = requireNotNull(System.getenv("BACKTEST_TEST_DB_USER"))
        val password = requireNotNull(System.getenv("BACKTEST_TEST_DB_PASSWORD"))
        val schema = "backtest_run_test_" + UUID.randomUUID().toString().replace("-", "")
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
            val bean = LocalContainerEntityManagerFactoryBean()
            val source = HikariDataSource().apply {
                jdbcUrl = url; username = user; this.password = password
                poolName = schema; maximumPoolSize = 4; minimumIdle = 0; connectionTimeout = 5000
                addDataSourceProperty("currentSchema", schema); addDataSourceProperty("ApplicationName", schema)
            }
            try {
                bean.dataSource = source; bean.jpaVendorAdapter = HibernateJpaVendorAdapter()
                bean.setPackagesToScan("com.refinvest.core.backtest.adapter.out.persistence")
                bean.setJpaPropertyMap(mapOf("hibernate.hbm2ddl.auto" to "create", "hibernate.jdbc.time_zone" to "UTC"))
                bean.afterPropertiesSet()
                (1..4).map { source.connection }.forEach { it.close() }
                test(Fixture(source, bean.`object`!!, schema))
            } finally {
                bean.destroy(); source.close()
                connection.createStatement().use {
                    val hasLob = it.executeQuery("""SELECT EXISTS(SELECT 1 FROM information_schema.columns
                        WHERE table_schema='$schema' AND table_name='backtest_results'
                        AND column_name='result_payload' AND udt_name='oid')""").use { rows -> rows.next(); rows.getBoolean(1) }
                    if (hasLob) it.execute("SELECT lo_unlink(result_payload) FROM $schema.backtest_results WHERE result_payload IS NOT NULL")
                    it.execute("DROP SCHEMA $schema CASCADE")
                }
            }
        }
    }
}
