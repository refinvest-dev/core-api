package com.refinvest

import com.refinvest.core.auth.adapter.out.persistence.RefreshSessionJpaEntity
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.*

@EnabledIfEnvironmentVariable(named = "AUTH_TEST_JDBC_URL", matches = ".+")
class AuthFamilyFenceMigrationTest {
    @Test
    fun `V12 preserves existing auth data and supports Hibernate validate`() {
        val url = requireNotNull(System.getenv("AUTH_TEST_JDBC_URL"))
        val user = requireNotNull(System.getenv("AUTH_TEST_DB_USER"))
        val password = requireNotNull(System.getenv("AUTH_TEST_DB_PASSWORD"))
        val schema = "auth_migration_test_" + UUID.randomUUID().toString().replace("-", "")
        DriverManager.getConnection(url, user, password).use { admin ->
            admin.createStatement().use { it.execute("CREATE SCHEMA $schema") }
            val source = HikariDataSource().apply {
                jdbcUrl = url; username = user; this.password = password
                maximumPoolSize = 2; minimumIdle = 0
                addDataSourceProperty("currentSchema", schema)
            }
            val bean = LocalContainerEntityManagerFactoryBean()
            try {
                val jdbc = JdbcTemplate(source)
                jdbc.execute("""CREATE TABLE refresh_sessions (
                    jti UUID PRIMARY KEY, member_id BIGINT NOT NULL, family_id UUID NOT NULL,
                    token_fingerprint VARCHAR(128) NOT NULL, issued_at TIMESTAMPTZ NOT NULL,
                    expires_at TIMESTAMPTZ NOT NULL, revoked_at TIMESTAMPTZ, replaced_by_jti UUID)""")
                jdbc.execute("""CREATE TABLE social_identities (
                    id BIGSERIAL PRIMARY KEY, provider VARCHAR(20) NOT NULL, provider_subject VARCHAR(255) NOT NULL,
                    member_id BIGINT NOT NULL, CONSTRAINT uk_social_identity_provider_subject UNIQUE(provider,provider_subject))""")
                jdbc.update("INSERT INTO social_identities(provider,provider_subject,member_id) VALUES ('GOOGLE','legacy-subject',1)")
                jdbc.execute("""INSERT INTO refresh_sessions VALUES
                    ('00000000-0000-0000-0000-000000000001',1,'00000000-0000-0000-0000-000000000010','synthetic-active',
                        '2026-10-01T00:00:00Z','2026-11-01T00:00:00Z',NULL,NULL),
                    ('00000000-0000-0000-0000-000000000002',1,'00000000-0000-0000-0000-000000000010','synthetic-rotated',
                        '2026-10-01T00:00:00Z','2026-11-01T00:00:00Z','2026-10-02T00:00:00Z','00000000-0000-0000-0000-000000000001')""")
                val sessions = jdbc.queryForList("SELECT * FROM refresh_sessions ORDER BY jti")
                val identities = jdbc.queryForList("SELECT * FROM social_identities ORDER BY id")
                val flyway = Flyway.configure().dataSource(source).schemas(schema).baselineOnMigrate(true).baselineVersion("11")
                    .locations("classpath:db/migration").target("12").load()
                assertEquals(1, flyway.migrate().migrationsExecuted)
                assertEquals(sessions, jdbc.queryForList("SELECT * FROM refresh_sessions ORDER BY jti"))
                assertEquals(identities, jdbc.queryForList("SELECT * FROM social_identities ORDER BY id"))
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM refresh_session_family_locks", Int::class.java))
                bean.dataSource = source
                bean.jpaVendorAdapter = HibernateJpaVendorAdapter()
                bean.setPackagesToScan("com.refinvest.core.auth.adapter.out.persistence")
                bean.setJpaPropertyMap(mapOf("hibernate.hbm2ddl.auto" to "validate", "hibernate.jdbc.time_zone" to "UTC"))
                bean.afterPropertiesSet()
                bean.`object`!!.createEntityManager().use { manager ->
                    val restored = manager.find(RefreshSessionJpaEntity::class.java, UUID(0, 2))
                    assertEquals(UUID(0, 1), restored.replacedByJti)
                }
                assertEquals(0, flyway.migrate().migrationsExecuted)
            } finally {
                bean.destroy(); source.close()
                admin.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
            }
        }
    }
}
