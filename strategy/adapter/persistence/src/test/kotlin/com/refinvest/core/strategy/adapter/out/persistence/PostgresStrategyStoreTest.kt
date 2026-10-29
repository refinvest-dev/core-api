package com.refinvest.core.strategy.adapter.out.persistence

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.strategy.application.define.DefineStrategyVersionService
import com.refinvest.core.strategy.domain.Strategy
import com.refinvest.core.strategy.domain.strategy.StrategyVersion
import com.refinvest.core.strategy.domain.valueobject.*
import com.refinvest.core.strategy.port.inbound.define.DefineStrategyVersionCommand
import com.refinvest.core.strategy.port.outbound.id.StrategyVersionIdGenerator
import com.refinvest.core.strategy.port.outbound.member.MemberIdProvider
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import org.springframework.jdbc.core.JdbcTemplate
import com.zaxxer.hikari.HikariDataSource
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.springframework.transaction.support.TransactionTemplate
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

@EnabledIfEnvironmentVariable(named = "STRATEGY_TEST_JDBC_URL", matches = ".+")
class PostgresStrategyStoreTest {
    @Test
    fun readerUsesCreationTimeThenNumericIdentityAfterReload() = withFixture { f ->
        val aggregate = f.load().apply {
            addVersion(version(10, createdAt = TIME.plusSeconds(1)))
            addVersion(version(300, createdAt = TIME.minusSeconds(1)))
            addVersion(version(9, createdAt = TIME.plusSeconds(1)))
            addVersion(version(20, createdAt = TIME.minusSeconds(2)))
        }
        f.tx.execute { f.store.save(aggregate) }
        val beforeReading = f.snapshot()
        repeat(2) {
            val detail = assertNotNull(f.tx.execute { f.reader.findById(StrategyId(1)) })
            val listed = assertNotNull(f.tx.execute { f.reader.findByMemberId(MemberId(1), 0, 10) }).items.single()
            assertEquals(listOf(20L, 300L, 11L, 9L, 10L), detail.versions.map { it.id.value })
            assertEquals(StrategyVersionId(10), detail.latestVersionId)
            assertEquals(detail, listed)
            assertEquals(command().conditions, detail.versions.last().conditions)
        }
        assertEquals(beforeReading, f.snapshot(), "Reading must not rewrite persisted versions or conditions")
    }

    @Test
    fun readerKeepsEmptyMissingAndMemberScopedResults() = withFixture { f ->
        f.tx.execute {
            f.store.save(strategy(2))
            f.store.save(Strategy.create(StrategyId(3), MemberId(99), "foreign", TIME).apply {
                addVersion(version(31, strategyId = 3))
            })
        }
        val ownPage = assertNotNull(f.tx.execute { f.reader.findByMemberId(MemberId(1), 0, 10) })
        assertEquals(2L, ownPage.total)
        assertEquals(setOf(1L, 2L), ownPage.items.map { it.id.value }.toSet())
        val empty = ownPage.items.single { it.id == StrategyId(2) }
        assertNull(empty.latestVersionId)
        assertEquals(emptyList(), empty.versions)
        assertEquals(empty, f.tx.execute { f.reader.findById(StrategyId(2)) })
        assertNull(f.tx.execute { f.reader.findById(StrategyId(999)) })
        val foreignPage = assertNotNull(f.tx.execute { f.reader.findByMemberId(MemberId(99), 0, 10) })
        assertEquals(1L, foreignPage.total)
        assertEquals(StrategyVersionId(31), foreignPage.items.single().latestVersionId)
    }

    @Test
    fun readerSeesCommittedAppendsWithoutChangingEarlierVersions() = withFixture { f ->
        val original = f.snapshot(11)
        assertEquals(StrategyVersionId(11), f.tx.execute { f.reader.findById(StrategyId(1)) }?.latestVersionId)
        f.define(12)
        assertEquals(StrategyVersionId(12), f.tx.execute { f.reader.findById(StrategyId(1)) }?.latestVersionId)
        val aggregate = f.load().apply { addVersion(version(9, createdAt = TIME.plusSeconds(1))) }
        f.tx.execute { f.store.save(aggregate) }
        assertEquals(StrategyVersionId(9), f.tx.execute { f.reader.findById(StrategyId(1)) }?.latestVersionId)
        assertEquals(StrategyVersionId(9), f.tx.execute { f.reader.findByMemberId(MemberId(1), 0, 10) }?.items?.single()?.latestVersionId)
        assertEquals(original, f.snapshot(11))
    }

    @Test
    fun staleAggregateSaveKeepsBothAppendedVersionsAndOriginalConditions() = withFixture { f ->
        val original = f.snapshot()
        val first = f.load()
        val second = f.load()
        first.addVersion(version(12))
        second.addVersion(version(13))

        f.tx.execute { f.store.save(first) }
        f.tx.execute { f.store.save(second) }

        assertEquals(listOf(11L, 12L, 13L), f.versionIds())
        assertEquals(original, f.snapshot(11))
    }

    @Test
    fun concurrentDefinitionsWaitForTheSameRootAndBothCommit() = withFixture { f ->
        val original = f.snapshot()
        val firstLoaded = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondGenerated = AtomicBoolean(false)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<Long> {
                f.define(12) {
                    firstLoaded.countDown()
                    check(releaseFirst.await(15, TimeUnit.SECONDS))
                }
            }
            assertTrue(firstLoaded.await(15, TimeUnit.SECONDS))
            val second = pool.submit<Long> { f.define(13) { secondGenerated.set(true) } }
            f.awaitLockWaiter()
            assertFalse(secondGenerated.get(), "A waiting command must not work with an old collection")
            releaseFirst.countDown()
            assertEquals(12L, first.get(15, TimeUnit.SECONDS))
            assertEquals(13L, second.get(15, TimeUnit.SECONDS))
            assertEquals(listOf(11L, 12L, 13L), f.versionIds())
            assertEquals(original, f.snapshot(11))
        } finally {
            releaseFirst.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS))
        }
    }

    @Test
    fun failedDefinitionRollsBackAndUnblocksTheNextWriter() = withFixture { f ->
        val original = f.snapshot()
        val saved = CountDownLatch(1)
        val rollback = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val failed = pool.submit<Boolean> {
                assertFailsWith<IllegalStateException> {
                    f.tx.execute {
                        f.define(12)
                        f.flush()
                        saved.countDown()
                        check(rollback.await(15, TimeUnit.SECONDS))
                        error("Synthetic failure after version and conditions insertion")
                    }
                }
                true
            }
            assertTrue(saved.await(15, TimeUnit.SECONDS))
            val next = pool.submit<Long> { f.define(13) }
            f.awaitLockWaiter()
            rollback.countDown()
            assertTrue(failed.get(15, TimeUnit.SECONDS))
            assertEquals(13L, next.get(15, TimeUnit.SECONDS))
            assertEquals(listOf(11L, 13L), f.versionIds())
            assertEquals(original, f.snapshot(11))
            assertEquals(0L, f.jdbc.queryForObject(
                "SELECT count(*) FROM strategy_version_conditions WHERE strategy_version_id=12", Long::class.java))
        } finally {
            rollback.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS))
        }
    }

    @Test
    fun repeatedSaveDoesNotRewritePinnedVersionsOrConditionRows() = withFixture { f ->
        f.jdbc.execute("CREATE TABLE synthetic_version_pin (version_id bigint REFERENCES strategy_versions(id))")
        f.jdbc.update("INSERT INTO synthetic_version_pin VALUES (11)")
        val original = f.snapshot()
        val aggregate = f.load()
        repeat(2) { f.tx.execute { f.store.save(aggregate) } }
        assertEquals(original, f.snapshot())
        assertEquals(11L, f.jdbc.queryForObject("SELECT version_id FROM synthetic_version_pin", Long::class.java))
    }

    @Test
    fun databaseTimestampPrecisionDoesNotTurnAnUnchangedSaveIntoAnEdit() = withFixture { f ->
        val timestamp = TIME.plusNanos(123456789)
        val aggregate = Strategy.create(StrategyId(2), MemberId(1), "nanosecond clock", timestamp).apply {
            addVersion(version(21, strategyId = 2, createdAt = timestamp))
        }
        f.tx.execute { f.store.save(aggregate) }
        val original = f.snapshot()
        f.tx.execute { f.store.save(aggregate) }
        assertEquals(original, f.snapshot())
    }

    @Test
    fun existingVersionContentCannotBeReplacedAndTheWholeSaveRollsBack() = withFixture { f ->
        val original = f.snapshot()
        val variants = listOf(
            version(11, createdAt = TIME.plusSeconds(1)),
            version(11, primary = AssetSymbol.SPY),
            version(11, execution = AssetSymbol.SOXL),
            version(11, lag = 9),
            version(11, holdingSessions = 6),
            version(11, conditions = listOf(command().conditions.first().copy(operandB = LiteralValue(-0.09)))),
            version(11, conditions = command().conditions.reversed().mapIndexed { index, condition ->
                condition.copy(logicalCombinator = if (index == 0) null else LogicalCombinator.OR)
            }),
        )
        for (variant in variants) {
            val changed = strategy().apply { addVersion(version(12)); addVersion(variant) }
            assertFailsWith<IllegalArgumentException> { f.tx.execute { f.store.save(changed) } }
            assertEquals(original, f.snapshot())
        }
    }

    @Test
    fun rootMetadataCannotBeReplaced() = withFixture { f ->
        val original = f.snapshot()
        val changed = Strategy.create(StrategyId(1), MemberId(99), "changed", TIME)
        assertFailsWith<IllegalArgumentException> { f.tx.execute { f.store.save(changed) } }
        assertEquals(original, f.snapshot())
    }

    @Test
    fun commandLoadRequiresAnEnclosingTransaction() = withFixture { f ->
        assertFailsWith<IllegalStateException> { f.store.findById(StrategyId(1)) }
    }

    @Test
    fun versionIdentityCollisionWithAnotherStrategyCannotMoveOrChangeIt() = withFixture { f ->
        val original = f.snapshot()
        val other = strategy(2).apply { addVersion(version(11, strategyId = 2, lag = 9)) }
        assertFails { f.tx.execute { f.store.save(other) } }
        assertEquals(original, f.snapshot())
        assertEquals(0L, f.jdbc.queryForObject("SELECT count(*) FROM strategies WHERE id=2", Long::class.java))
    }

    @Test
    fun unrelatedStrategiesDoNotShareACommandLock() = withFixture { f ->
        f.tx.execute { f.store.save(strategy(2)) }
        val locked = CountDownLatch(1)
        val unlock = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<Boolean> {
                f.tx.execute {
                    assertNotNull(f.store.findById(StrategyId(1)))
                    locked.countDown()
                    check(unlock.await(15, TimeUnit.SECONDS))
                }
                true
            }
            assertTrue(locked.await(15, TimeUnit.SECONDS))
            assertEquals(21L, pool.submit<Long> { f.define(21, strategyId = 2) }.get(10, TimeUnit.SECONDS))
            unlock.countDown()
            assertTrue(first.get(15, TimeUnit.SECONDS))
            assertEquals(listOf(21L), f.versionIds(2))
        } finally {
            unlock.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS))
        }
    }

    @Test
    fun missingOrForeignStrategyDoesNotGenerateOrPersistAVersion() = withFixture { f ->
        val original = f.snapshot()
        for ((id, member) in listOf(999L to 1L, 1L to 99L)) {
            val service = f.service(StrategyVersionIdGenerator { error("Must not generate an ID") }, member)
            assertNull(f.tx.execute { service.execute(command(id)) })
        }
        assertEquals(original, f.snapshot())
    }

    @Test
    fun detachedGraphMapsWithoutADatabaseAndTheBacktestProjectionPreservesItsDefinition() = withFixture { f ->
        val graph = f.detachedGraph()
        val original = f.snapshot()
        val restored = StrategyPersistenceMapper.toDomain(graph)
        assertEquals(listOf(11L), restored.versions.map { it.id.value })
        assertEquals(command().conditions, restored.versions.single().conditions)
        val read = StrategyReadModelMapper.toReadModel(graph)
        assertEquals(StrategyVersionId(11), read.latestVersionId)
        val backtest = assertNotNull(f.tx.execute { f.backtestReader.findById(11) })
        assertEquals(1L, backtest.strategyId)
        assertEquals(MemberId(1), backtest.ownerMemberId)
        assertEquals(setOf("QQQ", "SPY", "TQQQ"), backtest.assetSymbols)
        assertEquals("RETURN", backtest.definition.conditions.first().operandA.metric)
        assertEquals(5, backtest.definition.conditions.first().operandA.window)
        assertEquals("AND", backtest.definition.conditions.last().logicalCombinator)
        assertEquals(3, backtest.definition.lag)
        assertEquals(5, backtest.definition.holdingSignalSessions)
        assertEquals(original, f.snapshot())
        f.tx.execute { f.store.save(restored) }
        assertEquals(original, f.snapshot(), "Restoring and saving must not replace pinned rows")
    }

    private class Fixture(source: HikariDataSource, private val factory: jakarta.persistence.EntityManagerFactory,
                          private val applicationName: String) {
        val jdbc = JdbcTemplate(source)
        val tx = TransactionTemplate(JpaTransactionManager(factory).apply { dataSource = source })
        private val entityManager = SharedEntityManagerCreator.createSharedEntityManager(factory)
        private val repositories = JpaRepositoryFactory(entityManager)
        val store = JpaStrategyStoreAdapter(repositories.getRepository(StrategyJpaStore::class.java), entityManager)
        val reader = JpaStrategyReaderAdapter(repositories.getRepository(StrategyJpaReader::class.java))
        val backtestReader = JpaStrategyVersionBacktestReaderAdapter(repositories.getRepository(StrategyVersionJpaReader::class.java))
        fun detachedGraph(): StrategyJpaEntity = factory.createEntityManager().use { manager ->
            manager.find(StrategyJpaEntity::class.java, 1L).apply { versions.forEach { it.conditions.toList() } }
        }

        init { tx.execute { store.save(strategy().apply { addVersion(version(11)) }) } }

        fun load(): Strategy = assertNotNull(tx.execute { store.findById(StrategyId(1)) })
        fun flush() = entityManager.flush()
        fun define(id: Long, strategyId: Long = 1, beforeId: () -> Unit = {}): Long =
            assertNotNull(tx.execute {
                service(StrategyVersionIdGenerator { beforeId(); StrategyVersionId(id) }).execute(command(strategyId))
            }).id.value

        fun service(ids: StrategyVersionIdGenerator, member: Long = 1) = DefineStrategyVersionService(
            store, ids, MemberIdProvider { MemberId(member) }, Clock.fixed(TIME, ZoneOffset.UTC))

        fun versionIds(strategyId: Long = 1): List<Long> = jdbc.queryForList(
            "SELECT id FROM strategy_versions WHERE strategy_id=? ORDER BY id", Long::class.java, strategyId).map { requireNotNull(it) }

        fun snapshot(versionId: Long? = null): Any = listOf(
            jdbc.queryForList("SELECT * FROM strategies ORDER BY id"),
            jdbc.queryForList("SELECT * FROM strategy_versions" + (versionId?.let { " WHERE id=$it" } ?: "") + " ORDER BY id"),
            jdbc.queryForList("SELECT * FROM strategy_version_conditions" +
                (versionId?.let { " WHERE strategy_version_id=$it" } ?: "") + " ORDER BY id"))

        fun awaitLockWaiter() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (System.nanoTime() < deadline) {
                if (jdbc.queryForObject("""SELECT EXISTS(SELECT 1 FROM pg_stat_activity
                    WHERE application_name=? AND wait_event_type='Lock'
                    AND query ILIKE '%strategies%')""", Boolean::class.java, applicationName) == true) return
                Thread.sleep(10)
            }
            error("Expected a PostgreSQL Strategy root lock waiter")
        }
    }

    private fun withFixture(test: (Fixture) -> Unit) {
        val url = requireNotNull(System.getenv("STRATEGY_TEST_JDBC_URL"))
        val user = requireNotNull(System.getenv("STRATEGY_TEST_DB_USER"))
        val password = requireNotNull(System.getenv("STRATEGY_TEST_DB_PASSWORD"))
        val schema = "strategy_store_test_" + UUID.randomUUID().toString().replace("-", "")
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
                addDataSourceProperty("currentSchema", schema)
                addDataSourceProperty("ApplicationName", schema)
            }
            try {
                bean.dataSource = source
                bean.jpaVendorAdapter = HibernateJpaVendorAdapter()
                bean.setPackagesToScan("com.refinvest.core.strategy.adapter.out.persistence")
                bean.setJpaPropertyMap(mapOf("hibernate.hbm2ddl.auto" to "create", "hibernate.jdbc.time_zone" to "UTC"))
                bean.afterPropertiesSet()
                (1..4).map { source.connection }.forEach { it.close() }
                test(Fixture(source, requireNotNull(bean.`object`), schema))
            } finally {
                bean.destroy()
                source.close()
                connection.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
            }
        }
    }

    companion object {
        private val TIME = Instant.parse("2026-10-01T00:00:00Z")
        private fun strategy(id: Long = 1) = Strategy.create(StrategyId(id), MemberId(1), "synthetic strategy", TIME)
        private fun version(id: Long, strategyId: Long = 1, lag: Int = 3, createdAt: Instant = TIME,
                            primary: AssetSymbol = AssetSymbol.QQQ, execution: AssetSymbol = AssetSymbol.TQQQ,
                            holdingSessions: Int = 5, conditions: List<Condition> = command().conditions): StrategyVersion =
            StrategyVersion.create(StrategyVersionId(id), StrategyId(strategyId), createdAt, primary,
                conditions, execution, SignalSessions(lag), TimeBasedExit(holdingSessions))

        private fun command(id: Long = 1) = DefineStrategyVersionCommand(
            StrategyId(id), AssetSymbol.QQQ,
            listOf(
                Condition(ComparisonOperator.LT, operandA = MetricReference(AssetSymbol.QQQ, MetricType.RETURN, 5),
                    operandB = LiteralValue(-0.07)),
                Condition(ComparisonOperator.GTE, LogicalCombinator.AND,
                    MetricReference(AssetSymbol.SPY, MetricType.CHANGE, 3),
                    MetricOperand(MetricReference(AssetSymbol.QQQ, MetricType.RETURN, 2)))),
            AssetSymbol.TQQQ, SignalSessions(3), TimeBasedExit(5))
    }
}
