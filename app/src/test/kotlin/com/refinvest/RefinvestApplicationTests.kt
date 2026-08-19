package com.refinvest

import com.refinvest.core.strategy.port.outbound.StrategyIdGenerator
import com.refinvest.core.backtest.port.outbound.BacktestRunIdGenerator
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.TestPropertySource
import org.springframework.jdbc.core.JdbcTemplate
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:refinvest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
    ],
)
class RefinvestApplicationTests(
    @Autowired private val strategyIdGenerator: StrategyIdGenerator,
    @Autowired private val backtestRunIdGenerator: BacktestRunIdGenerator,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @LocalServerPort private val port: Int,
) {

	@Test
	fun contextLoads() {
	}

	@Test
	fun snowflakeStrategyIdGeneratorIsWired() {
		val first = strategyIdGenerator.next()
		val second = strategyIdGenerator.next()

		assertTrue(first.value > 0)
		assertNotEquals(first, second)
	}

    @Test
    fun `snowflake backtest run id generator is wired`() {
        val first = backtestRunIdGenerator.next()
        val second = backtestRunIdGenerator.next()

        assertTrue(first.value > 0)
        assertNotEquals(first, second)
    }

    @Test
    fun `health includes datasource status`() {
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://localhost:$port/actuator/health")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertTrue(response.statusCode() == 200)
        assertTrue(response.body().contains("\"status\":\"UP\""))
        assertTrue(response.body().contains("\"db\""), response.body())
    }

    @Test
    fun `creates a strategy through HTTP and persists it`() {
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://localhost:$port/strategies"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"volatility hypothesis\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )

        assertTrue(response.statusCode() == 201, response.body())
        val id = "\"id\":\"(\\d+)\"".toRegex().find(response.body())?.groupValues?.get(1)?.toLong()
        assertTrue(id != null, response.body())
        val persistedName = jdbcTemplate.queryForObject(
            "select name from strategies where id = ?",
            String::class.java,
            id,
        )
        val persistedMemberId = jdbcTemplate.queryForObject(
            "select member_id from strategies where id = ?",
            Long::class.java,
            id,
        )
        assertTrue(persistedName == "volatility hypothesis")
        assertTrue(persistedMemberId == 1L)
    }

    @Test
    fun `gets a strategy through HTTP after it is created`() {
        val created = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://localhost:$port/strategies"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"volatility hypothesis\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )
        val id = "\"id\":\"(\\d+)\"".toRegex().find(created.body())?.groupValues?.get(1)
        assertTrue(created.statusCode() == 201 && id != null, created.body())

        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://localhost:$port/strategies/$id")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertTrue(response.statusCode() == 200, response.body())
        assertTrue(response.body().contains("\"id\":\"$id\""), response.body())
        assertTrue(response.body().contains("\"name\":\"volatility hypothesis\""), response.body())
        assertTrue(response.body().contains("\"versions\":[]"), response.body())
    }

    @Test
    fun `defines a strategy version through HTTP and returns it from strategy retrieval`() {
        val created = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://localhost:$port/strategies"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"volatility hypothesis\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )
        val strategyId = "\"id\":\"(\\d+)\"".toRegex().find(created.body())?.groupValues?.get(1)
        assertTrue(created.statusCode() == 201 && strategyId != null, created.body())

        val defined = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://localhost:$port/strategies/$strategyId/versions"))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        """{
                        |  "primarySignalAsset":"QQQ",
                        |  "conditions":[{
                        |    "operator":"LT",
                        |    "operandA":{"asset":"QQQ","metric":"RETURN","window":5},
                        |    "operandB":-0.07
                        |  }],
                        |  "executionAsset":"TQQQ",
                        |  "lag":3,
                        |  "exit":{"holdingSignalSessions":5}
                        |}""".trimMargin(),
                    ),
                )
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )

        assertTrue(defined.statusCode() == 201, defined.body())
        assertTrue(defined.body().contains("\"strategyId\":\"$strategyId\""), defined.body())
        assertTrue(jdbcTemplate.queryForObject("select count(*) from strategy_versions", Long::class.java) == 1L)

        val retrieved = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://localhost:$port/strategies/$strategyId")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertTrue(retrieved.statusCode() == 200, retrieved.body())
        assertTrue(retrieved.body().contains("\"primarySignalAsset\":\"QQQ\""), retrieved.body())
        assertTrue(retrieved.body().contains("\"latestVersionId\":"), retrieved.body())
    }

    @Test
    fun `rejects VIX as an execution asset when defining a strategy version`() {
        val created = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://localhost:$port/strategies"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"volatility hypothesis\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )
        val strategyId = "\"id\":\"(\\d+)\"".toRegex().find(created.body())?.groupValues?.get(1)
        assertTrue(created.statusCode() == 201 && strategyId != null, created.body())

        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://localhost:$port/strategies/$strategyId/versions"))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        """{
                        |  "primarySignalAsset":"QQQ",
                        |  "conditions":[{
                        |    "operator":"GT",
                        |    "operandA":{"asset":"QQQ","metric":"SIMPLE"},
                        |    "operandB":1
                        |  }],
                        |  "executionAsset":"VIX",
                        |  "lag":0,
                        |  "exit":{"holdingSignalSessions":1}
                        |}""".trimMargin(),
                    ),
                )
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )

        assertTrue(response.statusCode() == 400, response.body())
        assertTrue(
            jdbcTemplate.queryForObject(
                "select count(*) from strategy_versions where strategy_id = ?",
                Long::class.java,
                strategyId.toLong(),
            ) == 0L,
        )
    }

    @Test
    fun `returns not found for an unknown strategy`() {
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://localhost:$port/strategies/999999999999999999")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertTrue(response.statusCode() == 404, response.body())
    }

    @Test
    fun `creates a pending backtest run through HTTP and persists it`() {
        seedStrategyVersion()
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://localhost:$port/strategy-versions/42/backtests"))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        """{
                        |  "period":{"start":"2025-01-01","end":"2025-12-31"},
                        |  "feeModel":{"commission":0.001,"slippage":0.002}
                        |}""".trimMargin(),
                    ),
                )
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )

        assertTrue(response.statusCode() == 202, response.body())
        val id = "\\\"id\\\":\\\"(\\d+)\\\"".toRegex().find(response.body())?.groupValues?.get(1)?.toLong()
        assertTrue(id != null, response.body())
        assertTrue(response.body().contains("\"strategyVersionId\":\"42\""), response.body())
        assertTrue(response.body().contains("\"strategyId\":\"7\""), response.body())
        assertTrue(response.body().contains("\"status\":\"PENDING\""), response.body())
        assertTrue(
            jdbcTemplate.queryForObject(
                "select status from backtest_runs where id = ?",
                String::class.java,
                id,
            ) == "PENDING",
        )
        assertTrue(
            jdbcTemplate.queryForObject(
                "select strategy_version_id from backtest_runs where id = ?",
                Long::class.java,
                id,
            ) == 42L,
        )
        assertTrue(
            jdbcTemplate.queryForObject(
                "select strategy_id from backtest_runs where id = ?",
                Long::class.java,
                id,
            ) == 7L,
        )
    }

    @Test
    fun `polls a pending backtest run through HTTP after it is created`() {
        seedStrategyVersion()
        val created = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://localhost:$port/strategy-versions/42/backtests"))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        """{
                        |  "period":{"start":"2025-01-01","end":"2025-12-31"},
                        |  "feeModel":{"commission":0.001,"slippage":0.002}
                        |}""".trimMargin(),
                    ),
                )
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )
        val runId = "\\\"id\\\":\\\"(\\d+)\\\"".toRegex().find(created.body())?.groupValues?.get(1)
        assertTrue(created.statusCode() == 202 && runId != null, created.body())

        val polled = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://localhost:$port/backtest-runs/$runId")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertTrue(polled.statusCode() == 200, polled.body())
        assertTrue(polled.body().contains("\"id\":\"$runId\""), polled.body())
        assertTrue(polled.body().contains("\"strategyVersionId\":\"42\""), polled.body())
        assertTrue(polled.body().contains("\"strategyId\":\"7\""), polled.body())
        assertTrue(polled.body().contains("\"status\":\"PENDING\""), polled.body())
    }

    @Test
    fun `returns not found when polling an unknown backtest run`() {
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://localhost:$port/backtest-runs/999999999999999999")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertTrue(response.statusCode() == 404, response.body())
    }

    @Test
    fun `rejects an invalid backtest period`() {
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://localhost:$port/strategy-versions/42/backtests"))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        """{
                        |  "period":{"start":"2025-12-31","end":"2025-01-01"},
                        |  "feeModel":{"commission":0.001,"slippage":0.002}
                        |}""".trimMargin(),
                    ),
                )
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertTrue(response.statusCode() == 400, response.body())
    }

    @Test
    fun `returns not found when a strategy version does not exist`() {
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://localhost:$port/strategy-versions/999/backtests"))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        """{
                        |  "period":{"start":"2025-01-01","end":"2025-12-31"},
                        |  "feeModel":{"commission":0.001,"slippage":0.002}
                        |}""".trimMargin(),
                    ),
                )
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertTrue(response.statusCode() == 404, response.body())
    }

    private fun seedStrategyVersion() {
        jdbcTemplate.update(
            "merge into strategies (id, member_id, name, created_at) key(id) values (7, 1, 'backtest fixture', CURRENT_TIMESTAMP)",
        )
        jdbcTemplate.update(
            """
            merge into strategy_versions (
                id, strategy_id, created_at, primary_signal_asset, execution_asset, lag, holding_signal_sessions
            ) key(id) values (42, 7, CURRENT_TIMESTAMP, 'QQQ', 'QQQ', 0, 1)
            """.trimIndent(),
        )
    }

}
