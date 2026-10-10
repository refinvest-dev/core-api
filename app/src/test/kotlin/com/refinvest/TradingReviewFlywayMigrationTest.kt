package com.refinvest

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

@EnabledIfEnvironmentVariable(named = "TRADING_REVIEW_TEST_JDBC_URL", matches = ".+")
class TradingReviewFlywayMigrationTest {
    @Test
    fun `Flyway migrates an existing PostgreSQL schema`() {
        val url = requireNotNull(System.getenv("TRADING_REVIEW_TEST_JDBC_URL"))
        val user = requireNotNull(System.getenv("TRADING_REVIEW_TEST_DB_USER"))
        val password = requireNotNull(System.getenv("TRADING_REVIEW_TEST_DB_PASSWORD"))
        val schema = "trading_flyway_test_" + UUID.randomUUID().toString().replace("-", "")
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE SCHEMA $schema")
                statement.execute("CREATE TABLE $schema.members (id BIGINT PRIMARY KEY, role VARCHAR(20) NOT NULL, created_at TIMESTAMPTZ NOT NULL)")
            }
            try {
                val flyway = Flyway.configure().dataSource(url, user, password).schemas(schema)
                    .baselineOnMigrate(true).baselineVersion("0")
                    .locations("classpath:db/migration").load()
                assertEquals(1, flyway.migrate().migrationsExecuted)
                connection.schema = schema
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema='$schema' AND table_name IN ('trading_accounts','trading_books','trading_idempotency','trading_deletion_requests')")
                        .use { result -> result.next(); assertEquals(4, result.getInt(1)) }
                }
            } finally {
                connection.schema = "public"
                connection.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
            }
        }
    }
}
