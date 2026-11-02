package com.refinvest

import com.refinvest.core.backtest.adapter.out.persistence.BacktestResultJpaEntity
import com.zaxxer.hikari.HikariDataSource
import jakarta.persistence.EntityManagerFactory
import jakarta.persistence.PersistenceException
import org.flywaydb.core.Flyway
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
    fun `empty database migrates every mapped feature and supports Hibernate validate`() = fixture { f ->
        f.flyway().migrate()
        f.orm("validate") { factory -> assertTrue(factory.metamodel.entities.size >= 14) }
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
                bean.setPackagesToScan("com.refinvest.core")
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
