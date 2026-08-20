package com.refinvest

import com.refinvest.core.backtest.domain.backtest.BacktestResult
import com.refinvest.core.backtest.domain.backtest.BacktestResultMetrics
import com.refinvest.core.backtest.domain.backtest.Benchmark
import com.refinvest.core.backtest.domain.backtest.BuyAndHoldResult
import com.refinvest.core.backtest.domain.backtest.DataIntegrityStatus
import com.refinvest.core.backtest.domain.backtest.SampleSizeWarning
import com.refinvest.core.backtest.domain.backtest.SignalExecutionDelay
import com.refinvest.core.backtest.domain.valueobject.BacktestRunId
import com.refinvest.core.backtest.domain.valueobject.DatasetSnapshotId
import com.refinvest.core.backtest.domain.valueobject.EngineVersion
import com.refinvest.core.backtest.domain.valueobject.Period
import com.refinvest.core.backtest.port.inbound.backtest.execution.CompleteBacktestRunCommand
import com.refinvest.core.backtest.port.inbound.backtest.execution.RecordBacktestRunExecutionUseCase
import com.refinvest.core.backtest.port.inbound.backtest.execution.StartBacktestRunCommand
import com.refinvest.core.strategy.port.outbound.StrategyIdGenerator
import com.refinvest.core.backtest.port.outbound.BacktestRunIdGenerator
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.TestPropertySource
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
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
    @Autowired private val recordBacktestRunExecutionUseCase: RecordBacktestRunExecutionUseCase,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val objectMapper: ObjectMapper,
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
    fun `lists the current member's strategies through HTTP with pagination`() {
        seedStrategy(8001L, 1L, "older strategy", "2099-01-01 00:00:00")
        seedStrategy(8002L, 1L, "newer strategy", "2099-02-01 00:00:00")
        seedStrategy(8003L, 2L, "another member strategy", "2099-03-01 00:00:00")

        val firstPage = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://localhost:$port/strategies?page=0&size=1"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertTrue(firstPage.statusCode() == 200, firstPage.body())
        assertTrue(firstPage.body().contains("\"id\":\"8002\""), firstPage.body())
        assertTrue(!firstPage.body().contains("another member strategy"), firstPage.body())
        assertTrue(firstPage.body().contains("\"page\":0"), firstPage.body())
        assertTrue(firstPage.body().contains("\"size\":1"), firstPage.body())

        val secondPage = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://localhost:$port/strategies?page=1&size=1"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertTrue(secondPage.statusCode() == 200, secondPage.body())
        assertTrue(secondPage.body().contains("\"id\":\"8001\""), secondPage.body())
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
    fun `previews a strategy draft through HTTP without persisting a version`() {
        val created = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://localhost:$port/strategies"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"preview hypothesis\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )
        val strategyId = "\"id\":\"(\\d+)\"".toRegex().find(created.body())?.groupValues?.get(1)
        assertTrue(created.statusCode() == 201 && strategyId != null, created.body())

        val preview = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://localhost:$port/strategies/$strategyId/versions/preview"))
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
            HttpResponse.BodyHandlers.ofString(),
        )

        assertTrue(preview.statusCode() == 200, preview.body())
        assertTrue(preview.body().contains("QQQ의 5일 수익률 -7% 미만"), preview.body())
        assertTrue(preview.body().contains("3 Signal Session 후 TQQQ를 매수"), preview.body())

        val strategy = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://localhost:$port/strategies/$strategyId")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertTrue(strategy.body().contains("\"versions\":[]"), strategy.body())
    }

    @Test
    fun `previews an incomplete strategy draft without rejecting empty conditions`() {
        seedStrategy(9001L, 2L, "preview fixture", "2099-01-01 00:00:00")

        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://localhost:$port/strategies/9001/versions/preview"))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        """{
                        |  "primarySignalAsset":"QQQ",
                        |  "conditions":[],
                        |  "executionAsset":"TQQQ",
                        |  "lag":0,
                        |  "exit":{"holdingSignalSessions":1}
                        |}""".trimMargin(),
                    ),
                )
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertTrue(response.statusCode() == 200, response.body())
        assertTrue(response.body().contains("조건을 입력해 주세요"), response.body())
    }

    @Test
    fun `returns not found when previewing an unknown strategy`() {
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://localhost:$port/strategies/999999/versions/preview"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertTrue(response.statusCode() == 404, response.body())
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
    fun `returns a persisted completed backtest result through HTTP`() {
        seedCompletedBacktestRun(99L)

        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://localhost:$port/backtest-runs/99")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertTrue(response.statusCode() == 200, response.body())
        assertTrue(response.body().contains("\"status\":\"COMPLETED\""), response.body())
        assertTrue(response.body().contains("\"datasetSnapshotId\":\"snapshot-99\""), response.body())
        assertTrue(response.body().contains("\"totalReturn\":0.10"), response.body())
        assertTrue(response.body().contains("\"backtestRunId\":\"99\""), response.body())
    }

    @Test
    fun `records a completed execution and exposes its persisted result through HTTP`() {
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
            HttpResponse.BodyHandlers.ofString(),
        )
        val runId = "\\\"id\\\":\\\"(\\d+)\\\"".toRegex().find(created.body())?.groupValues?.get(1)?.toLong()
        assertTrue(created.statusCode() == 202 && runId != null, created.body())

        recordBacktestRunExecutionUseCase.execute(
            StartBacktestRunCommand(
                backtestRunId = BacktestRunId(runId),
                actualPeriod = Period(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31)),
                datasetSnapshotId = DatasetSnapshotId("snapshot-$runId"),
                engineVersion = EngineVersion("engine-1"),
            ),
        )
        recordBacktestRunExecutionUseCase.execute(
            CompleteBacktestRunCommand(BacktestRunId(runId), completedResult(runId, "snapshot-$runId")),
        )

        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://localhost:$port/backtest-runs/$runId")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertTrue(response.statusCode() == 200, response.body())
        assertTrue(response.body().contains("\"status\":\"COMPLETED\""), response.body())
        assertTrue(response.body().contains("\"datasetSnapshotId\":\"snapshot-$runId\""), response.body())
        assertTrue(response.body().contains("\"backtestRunId\":\"$runId\""), response.body())
    }

    @Test
    fun `lists a strategy's backtest runs through HTTP with pagination`() {
        seedStrategy(777L)
        seedStrategy(778L)
        seedBacktestRun(7001L, 777L, "2025-01-01 00:00:00")
        seedBacktestRun(7002L, 777L, "2025-02-01 00:00:00")
        seedBacktestRun(7003L, 778L, "2025-03-01 00:00:00")

        val firstPage = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://localhost:$port/strategies/777/backtest-runs?page=0&size=1"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertTrue(firstPage.statusCode() == 200, firstPage.body())
        assertTrue(firstPage.body().contains("\"id\":\"7002\""), firstPage.body())
        assertTrue(firstPage.body().contains("\"total\":2"), firstPage.body())
        assertTrue(firstPage.body().contains("\"page\":0"), firstPage.body())
        assertTrue(firstPage.body().contains("\"size\":1"), firstPage.body())

        val secondPage = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://localhost:$port/strategies/777/backtest-runs?page=1&size=1"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertTrue(secondPage.statusCode() == 200, secondPage.body())
        assertTrue(secondPage.body().contains("\"id\":\"7001\""), secondPage.body())

        val missingStrategy = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://localhost:$port/strategies/999999/backtest-runs"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertTrue(missingStrategy.statusCode() == 404, missingStrategy.body())
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

    private fun seedStrategy(id: Long, memberId: Long, name: String, createdAt: String) {
        jdbcTemplate.update(
            "insert into strategies (id, member_id, name, created_at) values (?, ?, ?, ?)",
            id,
            memberId,
            name,
            java.sql.Timestamp.valueOf(createdAt),
        )
    }

    private fun seedCompletedBacktestRun(runId: Long) {
        jdbcTemplate.update(
            """
            insert into backtest_runs (
                id, strategy_id, strategy_version_id, requested_period_start, requested_period_end,
                commission, slippage, status, actual_period_start, actual_period_end,
                dataset_snapshot_id, engine_version, created_at
            ) values (?, 7, 42, '2025-01-01', '2025-12-31', 0.001, 0.002, 'COMPLETED',
                '2025-01-01', '2025-12-31', 'snapshot-99', 'engine-1', CURRENT_TIMESTAMP)
            """.trimIndent(),
            runId,
        )
        jdbcTemplate.update(
            "insert into backtest_results (backtest_run_id, result_payload) values (?, ?)",
            runId,
            objectMapper.writeValueAsString(completedResult(runId)),
        )
    }

    private fun seedStrategy(strategyId: Long) {
        jdbcTemplate.update(
            "merge into strategies (id, member_id, name, created_at) key(id) values (?, 1, 'backtest list fixture', CURRENT_TIMESTAMP)",
            strategyId,
        )
    }

    private fun seedBacktestRun(runId: Long, strategyId: Long, createdAt: String) {
        jdbcTemplate.update(
            """
            insert into backtest_runs (
                id, strategy_id, strategy_version_id, requested_period_start, requested_period_end,
                commission, slippage, status, created_at
            ) values (?, ?, 42, '2025-01-01', '2025-12-31', 0.001, 0.002, 'PENDING', ?)
            """.trimIndent(),
            runId,
            strategyId,
            java.sql.Timestamp.valueOf(createdAt),
        )
    }

    private fun completedResult(runId: Long, datasetSnapshotId: String = "snapshot-99"): BacktestResult = BacktestResult(
        backtestRunId = BacktestRunId(runId),
        metrics = BacktestResultMetrics(
            totalReturn = BigDecimal("0.10"), cagr = BigDecimal("0.08"), mdd = BigDecimal("0.03"),
            sharpe = BigDecimal("1.20"), winRate = BigDecimal("0.60"), tradeCount = 3,
            avgTradeReturn = BigDecimal("0.04"), avgHoldingPeriod = BigDecimal("5"), profitFactor = BigDecimal("1.50"),
        ),
        equityCurve = emptyList(),
        trades = emptyList(),
        benchmark = Benchmark(BuyAndHoldResult(BigDecimal("0.05"), BigDecimal("0.04"), BigDecimal("0.02")), null),
        signalExecutionDelay = SignalExecutionDelay(BigDecimal.ONE, BigDecimal.ONE, emptyList()),
        sampleSizeWarning = SampleSizeWarning.LOW,
        dataIntegrityStatus = DataIntegrityStatus(DatasetSnapshotId(datasetSnapshotId), true, true),
    )

}
