package com.refinvest

import com.refinvest.core.tradingreview.adapter.persistence.DeletionCheckpointJpaEntity
import com.refinvest.core.tradingreview.adapter.persistence.DeletionRequestJpaEntity
import com.refinvest.core.tradingreview.adapter.persistence.DeletionRequestJpaStore
import com.refinvest.core.tradingreview.adapter.persistence.DeletionRequestSnapshots
import com.refinvest.core.tradingreview.adapter.persistence.JpaDeletionRequestStoreAdapter
import com.refinvest.core.tradingreview.domain.DeletionRequest
import com.refinvest.core.tradingreview.domain.valueobject.DeletionAffectedResources
import com.refinvest.core.tradingreview.domain.valueobject.DeletionRequestId
import com.refinvest.core.tradingreview.domain.valueobject.DeletionScope

import com.refinvest.core.tradingreview.adapter.persistence.JpaTradingImportSessionStoreAdapter
import com.refinvest.core.tradingreview.adapter.persistence.TradingImportSessionJpaEntity
import com.refinvest.core.tradingreview.adapter.persistence.TradingImportSessionJpaStore
import com.refinvest.core.tradingreview.adapter.persistence.TradingImportSessionSnapshots
import com.refinvest.core.tradingreview.adapter.persistence.TradingSourceArtifactJpaEntity
import com.refinvest.core.tradingreview.adapter.persistence.TradingSourceArtifactJpaRows
import com.refinvest.core.tradingreview.domain.TradingImportSession
import com.refinvest.core.tradingreview.domain.valueobject.TradingArtifactRole
import com.refinvest.core.tradingreview.domain.valueobject.TradingDataPolicy
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportSessionId
import com.refinvest.core.tradingreview.domain.valueobject.TradingSourceArtifactId

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.adapter.persistence.JdbcTradingIdempotencyStoreAdapter
import com.refinvest.core.tradingreview.adapter.persistence.JpaTradingAccountStoreAdapter
import com.refinvest.core.tradingreview.adapter.persistence.JpaTradingBookStoreAdapter
import com.refinvest.core.tradingreview.adapter.persistence.TradingAccountJpaEntity
import com.refinvest.core.tradingreview.adapter.persistence.TradingAccountJpaStore
import com.refinvest.core.tradingreview.adapter.persistence.TradingBookJpaEntity
import com.refinvest.core.tradingreview.adapter.persistence.TradingBookJpaStore
import com.refinvest.core.tradingreview.domain.TradingAccount
import com.refinvest.core.tradingreview.domain.TradingBook
import com.refinvest.core.tradingreview.domain.valueobject.TradingAccountId
import com.refinvest.core.tradingreview.domain.valueobject.TradingBookId
import com.refinvest.core.tradingreview.domain.valueobject.TradingPositionMode
import com.refinvest.core.tradingreview.domain.valueobject.TradingProductFamily
import com.refinvest.core.tradingreview.domain.valueobject.TradingSettlementAsset
import com.refinvest.core.tradingreview.domain.valueobject.TradingVenue
import java.time.Instant
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.transaction.support.TransactionTemplate

import com.refinvest.core.backtest.adapter.out.persistence.BacktestResultJpaEntity
import com.zaxxer.hikari.HikariDataSource
import jakarta.persistence.EntityManagerFactory
import jakarta.persistence.PersistenceException
import org.flywaydb.core.Flyway
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.*

@EnabledIfEnvironmentVariable(named = "CORE_SCHEMA_TEST_JDBC_URL", matches = ".+")
class CoreSchemaMigrationTest {
    @Test
    fun `Trading JPA and JDBC share a pooled connection and roll back roots and idempotency together`() = fixture { f ->
        f.flyway().migrate()
        f.seed()
        f.orm("validate") { factory ->
            val em = SharedEntityManagerCreator.createSharedEntityManager(factory)
            val repositories = JpaRepositoryFactory(em)
            val accounts = JpaTradingAccountStoreAdapter(
                repositories.getRepository(TradingAccountJpaStore::class.java), em)
            val books = JpaTradingBookStoreAdapter(
                repositories.getRepository(TradingBookJpaStore::class.java),
                repositories.getRepository(TradingAccountJpaStore::class.java), em)
            val sessionRows = repositories.getRepository(TradingImportSessionJpaStore::class.java)
            val artifactRows = repositories.getRepository(TradingSourceArtifactJpaRows::class.java)
            val sessions = JpaTradingImportSessionStoreAdapter(
                sessionRows, artifactRows, repositories.getRepository(TradingAccountJpaStore::class.java),
                repositories.getRepository(TradingBookJpaStore::class.java),
                TradingImportSessionSnapshots(sessionRows, artifactRows, f.jdbc), em)
            val requestSnapshots = DeletionRequestSnapshots(
                repositories.getRepository(DeletionRequestJpaStore::class.java))
            val requests = JpaDeletionRequestStoreAdapter(requestSnapshots, em)
            val keys = JdbcTradingIdempotencyStoreAdapter(f.jdbc)
            val owner = MemberId(1)
            val clock = Instant.parse("2026-11-01T00:00:00Z")
            val account = TradingAccount.create(
                TradingAccountId(8001), owner,
                TradingVenue.BINANCE, "pooled", clock)
            val book = TradingBook.create(
                TradingBookId(8002), account, "pooled",
                TradingProductFamily.LINEAR_PERPETUAL,
                TradingPositionMode.ONE_WAY,
                TradingSettlementAsset.USDT, "UTC", clock)
            val tx = TransactionTemplate(JpaTransactionManager(factory))
            val rollback = IllegalStateException("synthetic pooled transaction rollback")
            assertSame(rollback, assertFailsWith<IllegalStateException> {
                tx.executeWithoutResult {
                    assertEquals((em.createNativeQuery("SELECT pg_backend_pid()").singleResult as Number).toLong(),
                        f.jdbc.queryForObject("SELECT pg_backend_pid()", Long::class.java))
                    accounts.insert(account)
                    books.insert(book)
                    val session = TradingImportSession.create(
                        TradingImportSessionId(8003), book, "UTC", "UTC",
                        TradingPositionMode.ONE_WAY, TradingDataPolicy(), "exact-pins", clock)
                    sessions.insert(session)
                    assertTrue(sessions.update(session.recordUpload(
                        TradingSourceArtifactId(8004),
                        TradingArtifactRole.TRADE_HISTORY,
                        "text/csv", 2, "a".repeat(64), "AUTO", "objects/pooled", byteArrayOf(1), "test", clock)))
                    assertEquals(1L, f.jdbc.queryForObject("SELECT count(*) FROM trading_source_artifacts", Long::class.java))
                    val current = sessions.find(session.id, owner, true)!!
                    assertTrue(sessions.update(current.requestDeletion(clock.plusSeconds(1))))
                    val request = DeletionRequest.request(
                        DeletionRequestId(8005), owner,
                        DeletionScope.importSession(session.id), 1,
                        DeletionAffectedResources(0, 0, 1, 1), clock.plusSeconds(1))
                    requests.insert(request)
                    assertEquals(request.id, requestSnapshots.find(request.id, owner)!!.id)
                    assertEquals(1L, f.jdbc.queryForObject("SELECT count(*) FROM trading_deletion_requests", Long::class.java))
                    keys.claim(owner, "createTradingAccount", "pooled-key", "a".repeat(64))
                    keys.complete(owner, "createTradingAccount", "pooled-key", account.id.value)
                    assertEquals(1L, f.jdbc.queryForObject("SELECT count(*) FROM trading_accounts", Long::class.java))
                    assertEquals(1L, f.jdbc.queryForObject("SELECT count(*) FROM trading_books", Long::class.java))
                    throw rollback
                }
            })
            listOf("trading_accounts", "trading_books", "trading_import_sessions", "trading_source_artifacts", "trading_deletion_requests", "trading_deletion_checkpoints", "trading_idempotency").forEach { table ->
                assertEquals(0L, f.jdbc.queryForObject("SELECT count(*) FROM $table", Long::class.java))
            }
        }
    }

    @Test
    fun `Boot auto configuration migrates before Hibernate validation`() = fixture { f ->
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FlywayAutoConfiguration::class.java))
            .withBean(javax.sql.DataSource::class.java, { f.source })
            .withPropertyValues("spring.flyway.enabled=true", "spring.flyway.baseline-on-migrate=true", "spring.flyway.baseline-version=0")
            .run { context ->
                assertNull(context.startupFailure)
                assertEquals(21, context.getBean(Flyway::class.java).info().applied().size)
                f.orm("validate")
            }
    }

    @Test
    fun `empty database migrates every mapped feature and supports Hibernate validate`() = fixture { f ->
        f.flyway().migrate()
        f.orm("validate") { factory ->
            assertTrue(factory.metamodel.entities.size >= 20)
            assertNotNull(factory.metamodel.entity(TradingAccountJpaEntity::class.java))
            assertNotNull(factory.metamodel.entity(TradingBookJpaEntity::class.java))
            assertNotNull(factory.metamodel.entity(TradingImportSessionJpaEntity::class.java))
            assertNotNull(factory.metamodel.entity(TradingSourceArtifactJpaEntity::class.java))
            assertNotNull(factory.metamodel.entity(DeletionRequestJpaEntity::class.java))
            assertNotNull(factory.metamodel.entity(DeletionCheckpointJpaEntity::class.java))
        }
        f.seed()
        f.assertReadableAndAppendable()
        assertFailsWith<DataIntegrityViolationException> {
            f.jdbc.update("INSERT INTO social_identities(provider,provider_subject,member_id) VALUES ('GOOGLE','retained',1)")
        }
        assertFailsWith<DataIntegrityViolationException> {
            f.jdbc.update("""INSERT INTO strategy_versions VALUES
                (999,999,now(),'QQQ','TQQQ',0,1)""")
        }
        assertEquals(0, f.flyway().migrate().migrationsExecuted)
    }

    @Test
    fun `V17 Hibernate schema upgrades without rewriting data indexes sequences or active reservations`() = fixture { f ->
        f.flyway("17").migrate()
        f.orm("update") // Reproduce the old production startup: Flyway followed by Hibernate update.
        f.seed()
        val rows = f.rows()
        val catalog = f.catalog()
        assertEquals(4, f.flyway().migrate().migrationsExecuted)
        assertEquals(rows, f.rows())
        assertEquals(catalog, f.catalog())
        f.assertReadableAndAppendable()
        assertEquals(0, f.flyway().migrate().migrationsExecuted)
    }

    @Test
    fun `pre Flyway schema keeps historical data and V11 reconstructs only active reservations`() = fixture { f ->
        f.orm("create")
        // Remove only the later technical tables from this isolated synthetic legacy schema.
        f.jdbc.execute("DROP TABLE backtest_capacity_reservations,backtest_capacities,refresh_session_family_locks")
        f.seed()
        val rows = f.rows()
        val catalog = f.catalog()
        assertEquals(21, f.flyway().migrate().migrationsExecuted)
        assertEquals(rows, f.rows().filterKeys { it in rows })
        assertTrue(f.catalog().containsAll(catalog))
        assertEquals(listOf(201L, 202L), f.jdbc.queryForList(
            "SELECT backtest_run_id FROM backtest_capacity_reservations ORDER BY backtest_run_id", Long::class.java))
        assertEquals(listOf(1L), f.jdbc.queryForList("SELECT member_id FROM backtest_capacities", Long::class.java))
        f.assertReadableAndAppendable()
        assertEquals(0, f.flyway().migrate().migrationsExecuted)
    }

    @Test
    fun `incompatible existing schema is rejected instead of being silently repaired`() = fixture { f ->
        f.flyway("17").migrate()
        f.orm("update")
        f.seed()
        f.jdbc.execute("ALTER TABLE strategies DROP COLUMN name")
        val rows = f.rows()
        f.flyway().migrate()
        val failure = assertFailsWith<PersistenceException> { f.orm("validate") }
        assertTrue(generateSequence<Throwable>(failure) { it.cause }.any {
            it.message.orEmpty().contains("missing column [name]")
        })
        assertEquals(rows, f.rows())
    }

    private class Fixture(val source: HikariDataSource, val schema: String) {
        val jdbc = JdbcTemplate(source)
        val ownedLargeObjects = mutableListOf<Long>()
        fun flyway(target: String? = null): Flyway = Flyway.configure().dataSource(source).schemas(schema)
            .baselineOnMigrate(true).baselineVersion("0").locations("classpath:db/migration")
            .apply { if (target != null) target(target) }.load()

        fun orm(action: String, test: (EntityManagerFactory) -> Unit = {}) {
            val bean = LocalContainerEntityManagerFactoryBean()
            try {
                bean.dataSource = source
                bean.jpaVendorAdapter = HibernateJpaVendorAdapter()
                if (action == "create") {
                    // Reproduce the historical pre-Flyway JPA model; Trading tables were introduced by Flyway.
                    bean.setPackagesToScan("com.refinvest.core.auth", "com.refinvest.core.member", "com.refinvest.core.user",
                        "com.refinvest.core.subscription", "com.refinvest.core.strategy", "com.refinvest.core.backtest")
                } else bean.setPackagesToScan("com.refinvest.core")
                bean.setJpaPropertyMap(mapOf("hibernate.hbm2ddl.auto" to action, "hibernate.jdbc.time_zone" to "UTC"))
                bean.afterPropertiesSet()
                test(requireNotNull(bean.`object`))
            } finally { bean.destroy() }
        }

        fun exists(table: String) = jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean::class.java, "$schema.$table") == true

        fun rows(): Map<String, List<String>> = TABLES.filter(::exists).associateWith { table ->
            jdbc.queryForList("SELECT row_to_json(t)::text FROM $table t ORDER BY row_to_json(t)::text", String::class.java)
                .map { requireNotNull(it) }
        }

        // Preserve existing constraint/index identities and sequence positions, not just row values.
        fun catalog(): List<String> = jdbc.queryForList("""
            SELECT concat(c.relname,':',c.oid,':',pg_get_indexdef(c.oid)) AS definition
            FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
            WHERE n.nspname=? AND c.relkind='i' AND c.relname NOT LIKE 'flyway_schema_history%'
            UNION ALL
            SELECT concat(c.relname,':',c.oid,':',pg_sequence_last_value(c.oid::regclass))
            FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
            WHERE n.nspname=? AND c.relkind='S' ORDER BY definition
        """, String::class.java, schema, schema).map { requireNotNull(it) }

        fun seed() {
            jdbc.execute("INSERT INTO members(id,role,created_at) VALUES (1,'MEMBER','2026-10-01T00:00:00Z')")
            jdbc.execute("INSERT INTO subscriptions(member_id,tier) VALUES (1,'PRO')")
            jdbc.execute("INSERT INTO social_identities(provider,provider_subject,member_id) VALUES ('GOOGLE','retained',1)")
            jdbc.execute("""INSERT INTO refresh_sessions(jti,member_id,family_id,token_fingerprint,issued_at,expires_at,revoked_at,replaced_by_jti) VALUES
                ('00000000-0000-0000-0000-000000000001',1,'00000000-0000-0000-0000-000000000010','active-fingerprint',
                 '2026-10-01T00:00:00Z','2026-12-01T00:00:00Z',NULL,NULL),
                ('00000000-0000-0000-0000-000000000002',1,'00000000-0000-0000-0000-000000000010','rotated-fingerprint',
                 '2026-10-01T00:00:00Z','2026-12-01T00:00:00Z','2026-10-02T00:00:00Z','00000000-0000-0000-0000-000000000001')""")
            if (exists("refresh_session_family_locks")) jdbc.execute("""INSERT INTO refresh_session_family_locks(family_id,revoked_at) VALUES
                ('00000000-0000-0000-0000-000000000010','2026-10-03T00:00:00Z')""")
            jdbc.execute("INSERT INTO strategies(id,member_id,name,created_at) VALUES (10,1,'Retained strategy','2026-10-01T00:00:00Z')")
            jdbc.execute("""INSERT INTO strategy_versions(id,strategy_id,created_at,primary_signal_asset,execution_asset,lag,holding_signal_sessions)
                VALUES (100,10,'2026-10-01T00:00:00Z','QQQ','TQQQ',1,5),(101,10,'2026-11-01T00:00:00Z','SPY','SOXL',0,2)""")
            jdbc.execute("""INSERT INTO strategy_version_conditions(strategy_version_id,condition_order,operator,operand_a_asset,
                operand_a_metric,operand_a_window,operand_b_kind,operand_b_literal) VALUES
                (100,0,'LT','QQQ','RETURN',5,'LITERAL',-0.07),(101,0,'GT','SPY','SIMPLE',NULL,'LITERAL',100)""")
            jdbc.execute("""INSERT INTO backtest_runs(id,strategy_id,strategy_version_id,requested_period_start,requested_period_end,
                commission,slippage,status,actual_period_start,actual_period_end,dataset_snapshot_id,engine_version,failure_reason,error_code,created_at) VALUES
                (200,10,100,'2026-01-01','2026-02-01',0.0001,0.0002,'COMPLETED','2026-01-01','2026-02-01','retained-snapshot','retained-engine',NULL,NULL,'2026-10-01T00:00:00Z'),
                (201,10,100,'2026-01-01','2026-02-01',0.0001,0.0002,'RUNNING','2026-01-01','2026-02-01','running-snapshot','running-engine',NULL,NULL,'2026-10-02T00:00:00Z'),
                (202,10,101,'2026-01-01','2026-02-01',0.0001,0.0002,'PENDING',NULL,NULL,NULL,NULL,NULL,NULL,'2026-11-01T00:00:00Z'),
                (203,10,100,'2026-01-01','2026-02-01',0.0001,0.0002,'FAILED',NULL,NULL,NULL,NULL,'retained failure','RETAINED_ERROR','2026-10-01T00:00:00Z')""")
            val oid = requireNotNull(jdbc.queryForObject("SELECT lo_from_bytea(0,?)::bigint", Long::class.java, RESULT.toByteArray(Charsets.UTF_8)))
            ownedLargeObjects += oid
            jdbc.update("INSERT INTO backtest_results(backtest_run_id,result_payload) VALUES (200,?::oid)", oid)
            jdbc.execute("""INSERT INTO backtest_compute_dispatches(backtest_run_id,idempotency_key,compute_run_id,status,next_attempt_at,
                lease_token,lease_expires_at,created_at,updated_at,dispatch_attempt_count,poll_retry_pending) VALUES
                (201,'00000000-0000-0000-0000-000000000201','retained-compute','SUBMITTED','2026-10-02T00:00:00Z',
                 '00000000-0000-0000-0000-000000000202','2026-12-01T00:00:00Z','2026-10-02T00:00:00Z','2026-10-03T00:00:00Z',3,true)""")
            jdbc.execute("INSERT INTO backtest_quotas(member_id,quota_month,used_count,active_count) VALUES (1,'2026-10-01',7,9),(1,'2026-11-01',8,2)")
            if (exists("backtest_capacities")) {
                jdbc.execute("INSERT INTO backtest_capacities VALUES (1)")
                jdbc.execute("INSERT INTO backtest_capacity_reservations(backtest_run_id,member_id,quota_month) VALUES (201,1,'2026-10-01'),(202,1,'2026-11-01')")
            }
        }

        fun assertReadableAndAppendable() {
            orm("validate") { factory ->
                factory.createEntityManager().use { manager ->
                    manager.transaction.begin()
                    assertEquals(RESULT, manager.find(BacktestResultJpaEntity::class.java, 200L).resultPayload)
                    manager.transaction.commit()
                }
            }
            assertEquals(2L, jdbc.queryForObject("""INSERT INTO social_identities(provider,provider_subject,member_id)
                VALUES ('GOOGLE','after-migration',1) RETURNING id""", Long::class.java))
            assertEquals(3L, jdbc.queryForObject("""INSERT INTO strategy_version_conditions(strategy_version_id,condition_order,
                operator,logical_combinator,operand_a_asset,operand_a_metric,operand_b_kind,operand_b_literal)
                VALUES (101,1,'GT','AND','SPY','SIMPLE','LITERAL',101) RETURNING id""", Long::class.java))
        }
    }

    private fun fixture(test: (Fixture) -> Unit) {
        val url = requireNotNull(System.getenv("CORE_SCHEMA_TEST_JDBC_URL"))
        val user = requireNotNull(System.getenv("CORE_SCHEMA_TEST_DB_USER"))
        val password = requireNotNull(System.getenv("CORE_SCHEMA_TEST_DB_PASSWORD"))
        val schema = "core_schema_test_" + UUID.randomUUID().toString().replace("-", "")
        DriverManager.getConnection(url, user, password).use { admin ->
            admin.createStatement().use { s ->
                s.executeQuery("SELECT current_database()").use { r ->
                    assertTrue(r.next()); assertEquals("refinvest_integration", r.getString(1))
                }
                s.execute("CREATE SCHEMA $schema")
            }
            val source = HikariDataSource().apply {
                jdbcUrl = url; username = user; this.password = password
                maximumPoolSize = 2; minimumIdle = 0
                addDataSourceProperty("currentSchema", schema)
            }
            val fixture = Fixture(source, schema)
            try { test(fixture) }
            finally {
                source.close()
                for (oid in fixture.ownedLargeObjects) admin.createStatement().use { it.execute("SELECT lo_unlink($oid::oid)") }
                admin.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
            }
        }
    }

    companion object {
        private const val RESULT = "{\"schemaVersion\":1,\"retained\":\"기존 결과\"}"
        private val TABLES = listOf("members", "subscriptions", "social_identities", "refresh_sessions", "refresh_session_family_locks",
            "strategies", "strategy_versions", "strategy_version_conditions", "backtest_runs", "backtest_results",
            "backtest_compute_dispatches", "backtest_quotas", "backtest_capacities", "backtest_capacity_reservations")
    }
}
