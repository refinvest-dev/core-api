package com.refinvest.core.backtest.adapter.out.persistence

import com.refinvest.core.backtest.port.outbound.persistence.BacktestQuotaReservation
import com.refinvest.core.backtest.port.outbound.persistence.BacktestQuotaRequest
import com.refinvest.core.backtest.domain.valueobject.BacktestRunId
import com.refinvest.core.shared.kernel.member.MemberId
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.sql.DriverManager
import java.time.YearMonth
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@EnabledIfEnvironmentVariable(named = "BACKTEST_TEST_JDBC_URL", matches = ".+")
class PostgresBacktestQuotaStoreTest {
    @Test
    fun activeRunFromPreviousMonthStillConsumesCapacity() = withDatabase { source ->
        val jdbc = JdbcTemplate(source)
        val tx = TransactionTemplate(DataSourceTransactionManager(source))
        migrate(jdbc, tx)
        val store = JdbcBacktestQuotaStoreAdapter(jdbc)
        assertEquals(BacktestQuotaReservation.RESERVED, tx.execute {
            store.reserve(request(1, "2025-12"))
        })
        assertEquals(BacktestQuotaReservation.CONCURRENCY_LIMIT_EXCEEDED, tx.execute {
            store.reserve(request(2, "2026-01"))
        })
        tx.execute { store.releaseConcurrentCapacity(BacktestRunId(1)) }
        assertEquals(BacktestQuotaReservation.RESERVED, tx.execute { store.reserve(request(2, "2026-01")) })
        // A duplicate/unknown terminal notification must never release Run 2's reservation.
        tx.execute {
            store.releaseConcurrentCapacity(BacktestRunId(1))
            store.releaseConcurrentCapacity(BacktestRunId(999))
        }
        assertEquals(BacktestQuotaReservation.CONCURRENCY_LIMIT_EXCEEDED,
            tx.execute { store.reserve(request(3, "2026-02")) })
        assertEquals(listOf(1, 1), jdbc.query("SELECT used_count FROM backtest_quotas ORDER BY quota_month", { rs, _ -> rs.getInt(1) }))
        assertEquals(1L, count(jdbc, "backtest_capacity_reservations"))
    }

    @Test
    fun monthlyRejectionDoesNotConsumeCapacityAndFailureDoesNotRefundUsage() = withDatabase { source ->
        val jdbc = JdbcTemplate(source)
        val tx = TransactionTemplate(DataSourceTransactionManager(source))
        migrate(jdbc, tx)
        val store = JdbcBacktestQuotaStoreAdapter(jdbc)
        val first = request(1, "2026-01").copy(monthlyExecutionLimit = 1)
        assertEquals(BacktestQuotaReservation.RESERVED, tx.execute { store.reserve(first) })
        tx.execute { store.releaseConcurrentCapacity(first.backtestRunId) }
        assertEquals(BacktestQuotaReservation.MONTHLY_LIMIT_EXCEEDED,
            tx.execute { store.reserve(first.copy(backtestRunId = BacktestRunId(2))) })
        assertEquals(0L, count(jdbc, "backtest_capacity_reservations"))
        assertEquals(BacktestQuotaReservation.RESERVED,
            tx.execute { store.reserve(request(3, "2026-02")) })
        assertEquals(BacktestQuotaReservation.RESERVED,
            tx.execute { store.reserve(request(4, "2026-02").copy(memberId = MemberId(2))) })
        assertEquals(1, jdbc.queryForObject("SELECT used_count FROM backtest_quotas WHERE member_id=1 AND quota_month='2026-01-01'", Int::class.java))
    }

    @Test
    fun admissionAndTerminalRollbackKeepUsageAndReservationsConsistent() = withDatabase { source ->
        val jdbc = JdbcTemplate(source)
        val tx = TransactionTemplate(DataSourceTransactionManager(source))
        migrate(jdbc, tx)
        val store = JdbcBacktestQuotaStoreAdapter(jdbc)
        assertFailsWith<IllegalStateException> {
            tx.execute {
                store.reserve(request(1, "2026-01"))
                error("Synthetic Run/dispatch persistence failure")
            }
        }
        assertEquals(0L, count(jdbc, "backtest_capacity_reservations"))
        assertEquals(0L, count(jdbc, "backtest_quotas"))
        assertEquals(BacktestQuotaReservation.RESERVED, tx.execute { store.reserve(request(1, "2026-01")) })
        assertFailsWith<IllegalStateException> {
            tx.execute {
                store.releaseConcurrentCapacity(BacktestRunId(1))
                error("Synthetic terminal persistence failure")
            }
        }
        assertEquals(BacktestQuotaReservation.CONCURRENCY_LIMIT_EXCEEDED,
            tx.execute { store.reserve(request(2, "2026-02")) })
    }

    @Test
    fun concurrentAdmissionsAcrossMonthsRespectOneMemberCapacity() = withDatabase { source ->
        val jdbc = JdbcTemplate(source)
        migrate(jdbc, TransactionTemplate(DataSourceTransactionManager(source)))
        val results = concurrently(8) { index ->
            TransactionTemplate(DataSourceTransactionManager(source)).execute {
                JdbcBacktestQuotaStoreAdapter(JdbcTemplate(source)).reserve(
                    request(index + 1L, if (index % 2 == 0) "2025-12" else "2026-01").copy(maxConcurrentRuns = 3))
            }
        }
        assertEquals(3, results.count { it == BacktestQuotaReservation.RESERVED })
        assertEquals(5, results.count { it == BacktestQuotaReservation.CONCURRENCY_LIMIT_EXCEEDED })
        assertEquals(3L, count(jdbc, "backtest_capacity_reservations"))
        assertEquals(3L, jdbc.queryForObject("SELECT sum(used_count) FROM backtest_quotas", Long::class.java))
    }

    @Test
    fun concurrentFirstAdmissionsRespectMonthlyLimit() = withDatabase { source ->
        val jdbc = JdbcTemplate(source)
        migrate(jdbc, TransactionTemplate(DataSourceTransactionManager(source)))
        val results = concurrently(6) { index ->
            TransactionTemplate(DataSourceTransactionManager(source)).execute {
                JdbcBacktestQuotaStoreAdapter(JdbcTemplate(source)).reserve(
                    request(index + 1L, "2026-01").copy(monthlyExecutionLimit = 1, maxConcurrentRuns = 10))
            }
        }
        assertEquals(1, results.count { it == BacktestQuotaReservation.RESERVED })
        assertEquals(5, results.count { it == BacktestQuotaReservation.MONTHLY_LIMIT_EXCEEDED })
        assertEquals(1L, count(jdbc, "backtest_capacity_reservations"))
        assertEquals(1, jdbc.queryForObject("SELECT used_count FROM backtest_quotas", Int::class.java))
    }

    @Test
    fun concurrentDuplicateReleaseCannotFreeAnotherRun() = withDatabase { source ->
        val jdbc = JdbcTemplate(source)
        val tx = TransactionTemplate(DataSourceTransactionManager(source))
        migrate(jdbc, tx)
        val store = JdbcBacktestQuotaStoreAdapter(jdbc)
        tx.execute {
            store.reserve(request(1, "2025-12").copy(maxConcurrentRuns = 2))
            store.reserve(request(2, "2026-01").copy(maxConcurrentRuns = 2))
        }
        concurrently(4) {
            TransactionTemplate(DataSourceTransactionManager(source)).execute {
                JdbcBacktestQuotaStoreAdapter(JdbcTemplate(source)).releaseConcurrentCapacity(BacktestRunId(1))
            }
        }
        assertEquals(listOf(2L), jdbc.query("SELECT backtest_run_id FROM backtest_capacity_reservations", { rs, _ -> rs.getLong(1) }))
        assertEquals(BacktestQuotaReservation.RESERVED,
            tx.execute { store.reserve(request(3, "2026-02").copy(maxConcurrentRuns = 2)) })
        assertEquals(BacktestQuotaReservation.CONCURRENCY_LIMIT_EXCEEDED,
            tx.execute { store.reserve(request(4, "2026-02").copy(maxConcurrentRuns = 2)) })
    }

    @Test
    fun replayOfActiveReservationDoesNotConsumeMonthlyUsageAgain() = withDatabase { source ->
        val jdbc = JdbcTemplate(source)
        val tx = TransactionTemplate(DataSourceTransactionManager(source))
        migrate(jdbc, tx)
        val store = JdbcBacktestQuotaStoreAdapter(jdbc)
        repeat(2) { assertEquals(BacktestQuotaReservation.RESERVED, tx.execute { store.reserve(request(1, "2026-01")) }) }
        assertEquals(1, jdbc.queryForObject("SELECT used_count FROM backtest_quotas", Int::class.java))
        assertFailsWith<IllegalArgumentException> {
            tx.execute { store.reserve(request(1, "2026-02").copy(memberId = MemberId(2))) }
        }
        assertFailsWith<IllegalStateException> { store.reserve(request(2, "2026-01")) }
        assertFailsWith<IllegalStateException> { store.releaseConcurrentCapacity(BacktestRunId(1)) }
    }

    @Test
    fun migrationBackfillsActiveRunsAcrossUtcMonthsAndPreservesMonthlyUsage() = withDatabase { source ->
        val jdbc = JdbcTemplate(source)
        val tx = TransactionTemplate(DataSourceTransactionManager(source))
        legacyTables(jdbc)
        jdbc.update("INSERT INTO strategies VALUES (11,1)")
        jdbc.update("INSERT INTO backtest_quotas VALUES (1,'2025-12-01',7,99)")
        jdbc.update("INSERT INTO backtest_runs VALUES (1,11,'RUNNING','2025-12-31T23:59:59Z'),(2,11,'PENDING','2026-01-01T00:00:00Z'),(3,11,'COMPLETED','2026-01-01T00:00:00Z')")
        migrate(jdbc, tx)
        assertEquals(listOf("2025-12-01", "2026-01-01"), jdbc.query(
            "SELECT quota_month::text FROM backtest_capacity_reservations ORDER BY backtest_run_id", { rs, _ -> rs.getString(1) }))
        assertEquals(7, jdbc.queryForObject("SELECT used_count FROM backtest_quotas", Int::class.java))
        val store = JdbcBacktestQuotaStoreAdapter(jdbc)
        assertEquals(BacktestQuotaReservation.CONCURRENCY_LIMIT_EXCEEDED,
            tx.execute { store.reserve(request(4, "2026-02").copy(maxConcurrentRuns = 2)) })
        tx.execute { store.releaseConcurrentCapacity(BacktestRunId(1)) }
        assertEquals(BacktestQuotaReservation.RESERVED,
            tx.execute { store.reserve(request(4, "2026-02").copy(maxConcurrentRuns = 2)) })
    }

    @Test
    fun migrationRejectsUnresolvableActiveOwnerAndRollsBack() = withDatabase { source ->
        val jdbc = JdbcTemplate(source)
        val tx = TransactionTemplate(DataSourceTransactionManager(source))
        legacyTables(jdbc)
        jdbc.update("INSERT INTO backtest_quotas VALUES (1,'2026-01-01',7,1)")
        jdbc.update("INSERT INTO backtest_runs VALUES (1,99,'RUNNING','2026-01-01T00:00:00Z')")
        assertFailsWith<org.springframework.dao.DataAccessException> { migrate(jdbc, tx) }
        assertEquals(7, jdbc.queryForObject("SELECT used_count FROM backtest_quotas", Int::class.java))
        assertEquals(null, jdbc.queryForObject("SELECT to_regclass('backtest_capacity_reservations')::text", String::class.java))
    }

    private fun request(runId: Long, month: String) =
        BacktestQuotaRequest(MemberId(1), BacktestRunId(runId), YearMonth.parse(month), 30, 1)

    private fun migrate(jdbc: JdbcTemplate, tx: TransactionTemplate) {
        val sql = requireNotNull(javaClass.getResourceAsStream("/db/migration/V11__backtest_capacity_reservations.sql"))
            .bufferedReader().use { it.readText() }
        tx.execute { jdbc.execute(sql) }
    }

    private fun legacyTables(jdbc: JdbcTemplate) {
        jdbc.execute("CREATE TABLE strategies(id BIGINT PRIMARY KEY, member_id BIGINT NOT NULL)")
        jdbc.execute("CREATE TABLE backtest_runs(id BIGINT PRIMARY KEY,strategy_id BIGINT NOT NULL,status TEXT NOT NULL,created_at TIMESTAMPTZ NOT NULL)")
        jdbc.execute("CREATE TABLE backtest_quotas(member_id BIGINT,quota_month DATE,used_count INTEGER,active_count INTEGER,PRIMARY KEY(member_id,quota_month))")
    }

    private fun count(jdbc: JdbcTemplate, table: String) = jdbc.queryForObject("SELECT count(*) FROM $table", Long::class.java)

    private fun <T> concurrently(workers: Int, work: (Int) -> T): List<T> {
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(workers)
        try {
            val tasks = (0 until workers).map { index -> pool.submit<T> {
                ready.countDown()
                check(start.await(20, TimeUnit.SECONDS))
                work(index)
            } }
            assertTrue(ready.await(20, TimeUnit.SECONDS))
            start.countDown()
            return tasks.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            pool.shutdownNow()
        }
    }

    private fun withDatabase(test: (DriverManagerDataSource) -> Unit) {
        val url = requireNotNull(System.getenv("BACKTEST_TEST_JDBC_URL"))
        val user = requireNotNull(System.getenv("BACKTEST_TEST_DB_USER"))
        val password = requireNotNull(System.getenv("BACKTEST_TEST_DB_PASSWORD"))
        val schema = "backtest_quota_test_" + UUID.randomUUID().toString().replace("-", "")
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
            try {
                test(DriverManagerDataSource(url, user, password).apply {
                    setConnectionProperties(java.util.Properties().apply { setProperty("currentSchema", schema) })
                })
            } finally {
                connection.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
            }
        }
    }
}
