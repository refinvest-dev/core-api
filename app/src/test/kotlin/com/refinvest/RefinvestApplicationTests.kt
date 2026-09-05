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
import com.refinvest.core.backtest.port.inbound.execution.CompleteBacktestRunCommand
import com.refinvest.core.backtest.port.inbound.execution.RecordBacktestRunExecutionUseCase
import com.refinvest.core.backtest.port.inbound.execution.StartBacktestRunCommand
import com.refinvest.core.strategy.port.outbound.id.StrategyIdGenerator
import com.refinvest.core.backtest.port.outbound.id.BacktestRunIdGenerator
import com.refinvest.core.auth.domain.RefreshSession
import com.refinvest.core.auth.port.outbound.token.AuthenticationTokenIssuer
import com.refinvest.core.auth.port.outbound.token.IssuedAuthenticationTokens
import com.refinvest.core.auth.port.outbound.persistence.RefreshSessionStore
import com.refinvest.core.member.port.inbound.create.CreateMemberCommand
import com.refinvest.core.member.port.inbound.create.CreateMemberUseCase
import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.asset.domain.Asset
import com.refinvest.core.asset.domain.AssetAvailability
import com.refinvest.core.asset.domain.SeriesSnapshot
import com.refinvest.core.asset.port.outbound.compute.AssetDataClient
import com.refinvest.core.asset.port.outbound.compute.AssetSeriesQuery
import com.refinvest.core.asset.port.outbound.compute.SeriesDataErrorCode
import com.refinvest.core.asset.port.outbound.compute.SeriesDataErrorException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.TestPropertySource
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AssetDataClientTestConfiguration::class)
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
    @Autowired private val jwtEncoder: JwtEncoder,
    @Autowired private val jwtDecoder: JwtDecoder,
    @Autowired private val authenticationTokenIssuer: AuthenticationTokenIssuer,
    @Autowired private val refreshSessionStore: RefreshSessionStore,
    @Autowired private val createMemberUseCase: CreateMemberUseCase,
    @LocalServerPort private val port: Int,
) {

    @BeforeEach
    fun clearBacktestQuotaReservations() {
        jdbcTemplate.update("delete from backtest_quotas")
    }

	@Test
	fun contextLoads() {
	}

    @Test
    fun `creates and persists a default free subscription for a new member`() {
        val member = createMemberUseCase.execute(CreateMemberCommand)

        assertEquals(
            "FREE",
            jdbcTemplate.queryForObject(
                "select tier from subscriptions where member_id = ?",
                String::class.java,
                member.memberId.value,
            ),
        )
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
    fun `rejects an unauthenticated protected request`() {
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://localhost:$port/strategies")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertTrue(response.statusCode() == 401, response.body())
        assertTrue(response.body().contains("\"code\":\"UNAUTHORIZED\""), response.body())
        assertTrue(response.body().contains("\"message\":"), response.body())
    }

    @Test
    fun `returns the current member from persisted role rather than access token claims`() {
        jdbcTemplate.update(
            "merge into members (id, role, created_at) key(id) values (1, 'ADMIN', CURRENT_TIMESTAMP)",
        )

        val response = authenticatedHttpClient().send(
            authenticatedRequest(URI("http://localhost:$port/auth/me")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertEquals(200, response.statusCode(), response.body())
        assertTrue(response.body().contains("\"memberId\":\"1\""), response.body())
        assertTrue(response.body().contains("\"role\":\"ADMIN\""), response.body())
    }

    @Test
    fun `returns the current member monthly backtest usage`() {
        jdbcTemplate.update(
            "merge into members (id, role, created_at) key(id) values (501, 'MEMBER', CURRENT_TIMESTAMP)",
        )
        jdbcTemplate.update(
            "merge into subscriptions (member_id, tier) key(member_id) values (501, 'PRO')",
        )
        jdbcTemplate.update(
            "insert into strategies (id, member_id, name, created_at) values (901, 501, 'usage fixture', CURRENT_TIMESTAMP)",
        )
        repeat(2) { offset ->
            jdbcTemplate.update(
                """
                insert into backtest_runs (
                    id, strategy_id, strategy_version_id, requested_period_start, requested_period_end,
                    commission, slippage, status, created_at
                ) values (?, 901, 42, '2025-01-01', '2025-12-31', 0.001, 0.002, 'PENDING', CURRENT_TIMESTAMP)
                """.trimIndent(),
                9001L + offset,
            )
        }

        val response = authenticatedHttpClient().send(
            authenticatedRequest(URI("http://localhost:$port/me/usage"), accessToken(memberId = 501)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertEquals(200, response.statusCode(), response.body())
        assertTrue(response.body().contains("\"tier\":\"PRO\""), response.body())
        assertTrue(response.body().contains("\"backtestsUsedThisMonth\":2"), response.body())
    }

    @Test
    fun `upgrades the current member subscription through HTTP`() {
        jdbcTemplate.update(
            "merge into members (id, role, created_at) key(id) values (502, 'MEMBER', CURRENT_TIMESTAMP)",
        )
        jdbcTemplate.update(
            "merge into subscriptions (member_id, tier) key(member_id) values (502, 'FREE')",
        )

        val response = authenticatedHttpClient().send(
            authenticatedRequest(
                URI("http://localhost:$port/me/subscription/upgrade"),
                accessToken(memberId = 502),
            )
                .header("X-XSRF-TOKEN", "test-csrf")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertEquals(200, response.statusCode(), response.body())
        assertTrue(response.body().contains("\"tier\":\"PRO\""), response.body())
        assertEquals(
            "PRO",
            jdbcTemplate.queryForObject(
                "select tier from subscriptions where member_id = ?",
                String::class.java,
                502L,
            ),
        )
    }

    @Test
    fun `rejects an access token whose member no longer exists`() {
        val response = authenticatedHttpClient().send(
            authenticatedRequest(
                URI("http://localhost:$port/auth/me"),
                accessToken(memberId = 999_999_999),
            ).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertEquals(401, response.statusCode(), response.body())
    }

    @Test
    fun `rejects a state changing request without CSRF header`() {
        val response = HttpClient.newHttpClient().send(
            authenticatedRequest(URI("http://localhost:$port/strategies"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"csrf protected\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertTrue(response.statusCode() == 403, "status=${response.statusCode()}, body=${response.body()}")
        assertTrue(response.body().contains("\"code\":\"FORBIDDEN\""), response.body())
        assertTrue(response.body().contains("\"message\":"), response.body())
    }

    @Test
    fun `rejects an access token with a different audience`() {
        val response = HttpClient.newHttpClient().send(
            authenticatedRequest(
                URI("http://localhost:$port/strategies"),
                accessToken(audience = "another-api", validate = false),
            ).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertTrue(response.statusCode() == 401, response.body())
    }

    @Test
    fun `rejects an expired access token`() {
        val response = HttpClient.newHttpClient().send(
            authenticatedRequest(
                URI("http://localhost:$port/strategies"),
                accessToken(
                    issuedAt = Instant.now().minusSeconds(7200),
                    expiresAt = Instant.now().minusSeconds(3600),
                    validate = false,
                ),
            ).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertTrue(response.statusCode() == 401, response.body())
    }

    @Test
    fun `rejects an access token with an invalid signature`() {
        val issuedToken = accessToken(validate = false)
        val tokenParts = issuedToken.split('.')
        val originalSignature = tokenParts[2]
        val invalidSignature = originalSignature.replaceRange(
            0,
            1,
            if (originalSignature.first() == 'A') "B" else "A",
        )
        val token = "${tokenParts[0]}.${tokenParts[1]}.$invalidSignature"
        val response = HttpClient.newHttpClient().send(
            authenticatedRequest(URI("http://localhost:$port/strategies"), token).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertTrue(response.statusCode() == 401, response.body())
        assertTrue(response.body().contains("\"code\":\"UNAUTHORIZED\""), response.body())
        assertTrue(response.body().contains("\"message\":"), response.body())
    }

    @Test
    fun `rejects a refresh token presented as an access cookie`() {
        val refreshToken = issueRefreshSession(memberId = 403L).refreshToken
        val response = HttpClient.newHttpClient().send(
            authenticatedRequest(URI("http://localhost:$port/strategies"), refreshToken).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertTrue(response.statusCode() == 401, response.body())
    }

    @Test
    fun `starts OAuth authorization even when an invalid access cookie is present`() {
        val response = HttpClient.newHttpClient().send(
            authenticatedRequest(
                URI("http://localhost:$port/oauth2/authorization/google"),
                accessToken(validate = false).dropLast(1) + "x",
            ).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertTrue(response.statusCode() in 300..399, response.body())
        assertTrue(response.headers().firstValue("location").orElse("").startsWith("https://accounts.google.com/"))
    }

    @Test
    fun `csrf endpoint issues a javascript-readable csrf cookie`() {
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://localhost:$port/auth/csrf")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        val csrfCookie = response.headers().allValues("set-cookie")
            .single { it.startsWith("XSRF-TOKEN=") }
        assertEquals(204, response.statusCode(), response.body())
        assertTrue(!csrfCookie.contains("HttpOnly"), csrfCookie)
    }

    @Test
    fun `refresh endpoint rotates cookies and detects refresh token replay`() {
        val issued = issueRefreshSession(memberId = 401L)

        val first = refresh(issued.refreshToken)

        assertEquals(204, first.statusCode(), first.body())
        val cookies = first.headers().allValues("set-cookie")
        assertTrue(cookies.any { it.startsWith("REFINVEST_ACCESS_TOKEN=") && it.contains("HttpOnly") }, cookies.toString())
        assertTrue(cookies.any { it.startsWith("REFINVEST_REFRESH_TOKEN=") && it.contains("Path=/auth") }, cookies.toString())
        assertFalse(refreshSession(issued.refreshJti).revokedAt == null)
        assertEquals(1L, jdbcTemplate.queryForObject(
            "select count(*) from refresh_sessions where family_id = ? and revoked_at is null",
            Long::class.java,
            issued.refreshFamilyId,
        ))

        val replay = refresh(issued.refreshToken)

        assertEquals(401, replay.statusCode(), replay.body())
        assertEquals(0L, jdbcTemplate.queryForObject(
            "select count(*) from refresh_sessions where family_id = ? and revoked_at is null",
            Long::class.java,
            issued.refreshFamilyId,
        ))
    }

    @Test
    fun `logout revokes refresh family, invalidates servlet session, and clears authentication cookies`() {
        val issued = issueRefreshSession(memberId = 402L)
        val oauthAuthorization = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
            .send(
                HttpRequest.newBuilder(URI("http://localhost:$port/oauth2/authorization/google"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        val sessionId = oauthAuthorization.headers().allValues("set-cookie")
            .single { it.startsWith("JSESSIONID=") }
            .substringAfter("JSESSIONID=")
            .substringBefore(';')
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://localhost:$port/auth/logout"))
                .header("Cookie", "JSESSIONID=$sessionId; REFINVEST_REFRESH_TOKEN=${issued.refreshToken}; XSRF-TOKEN=test-csrf")
                .header("X-XSRF-TOKEN", "test-csrf")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertEquals(204, response.statusCode(), response.body())
        assertFalse(refreshSession(issued.refreshJti).revokedAt == null)
        val cookies = response.headers().allValues("set-cookie")
        assertTrue(cookies.any { it.startsWith("REFINVEST_ACCESS_TOKEN=") && it.contains("Max-Age=0") }, cookies.toString())
        assertTrue(cookies.any { it.startsWith("REFINVEST_REFRESH_TOKEN=") && it.contains("Max-Age=0") }, cookies.toString())
        assertTrue(cookies.any { it.startsWith("JSESSIONID=") && it.contains("Max-Age=0") }, cookies.toString())
    }

    @Test
    fun `creates a strategy through HTTP and persists it`() {
        val response = authenticatedHttpClient().send(
            authenticatedRequest(URI("http://localhost:$port/strategies"))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", "test-csrf")
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
        val created = authenticatedHttpClient().send(
            authenticatedRequest(URI("http://localhost:$port/strategies"))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", "test-csrf")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"volatility hypothesis\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )
        val id = "\"id\":\"(\\d+)\"".toRegex().find(created.body())?.groupValues?.get(1)
        assertTrue(created.statusCode() == 201 && id != null, created.body())

        val response = authenticatedHttpClient().send(
            authenticatedRequest(URI("http://localhost:$port/strategies/$id")).GET().build(),
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

        val firstPage = authenticatedHttpClient().send(
            authenticatedRequest(URI("http://localhost:$port/strategies?page=0&size=1"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertTrue(firstPage.statusCode() == 200, firstPage.body())
        assertTrue(firstPage.body().contains("\"id\":\"8002\""), firstPage.body())
        assertTrue(!firstPage.body().contains("another member strategy"), firstPage.body())
        assertTrue(firstPage.body().contains("\"page\":0"), firstPage.body())
        assertTrue(firstPage.body().contains("\"size\":1"), firstPage.body())

        val secondPage = authenticatedHttpClient().send(
            authenticatedRequest(URI("http://localhost:$port/strategies?page=1&size=1"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertTrue(secondPage.statusCode() == 200, secondPage.body())
        assertTrue(secondPage.body().contains("\"id\":\"8001\""), secondPage.body())
    }

    @Test
    fun `defines a strategy version through HTTP and returns it from strategy retrieval`() {
        val created = authenticatedHttpClient().send(
            authenticatedRequest(URI("http://localhost:$port/strategies"))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", "test-csrf")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"volatility hypothesis\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )
        val strategyId = "\"id\":\"(\\d+)\"".toRegex().find(created.body())?.groupValues?.get(1)
        assertTrue(created.statusCode() == 201 && strategyId != null, created.body())

        val defined = authenticatedHttpClient().send(
            authenticatedRequest(URI("http://localhost:$port/strategies/$strategyId/versions"))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", "test-csrf")
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

        val retrieved = authenticatedHttpClient().send(
            authenticatedRequest(URI("http://localhost:$port/strategies/$strategyId")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertTrue(retrieved.statusCode() == 200, retrieved.body())
        assertTrue(retrieved.body().contains("\"primarySignalAsset\":\"QQQ\""), retrieved.body())
        assertTrue(retrieved.body().contains("\"latestVersionId\":"), retrieved.body())
    }

    @Test
    fun `previews a strategy draft through HTTP without persisting a version`() {
        val created = authenticatedHttpClient().send(
            authenticatedRequest(URI("http://localhost:$port/strategies"))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", "test-csrf")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"preview hypothesis\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )
        val strategyId = "\"id\":\"(\\d+)\"".toRegex().find(created.body())?.groupValues?.get(1)
        assertTrue(created.statusCode() == 201 && strategyId != null, created.body())

        val preview = authenticatedHttpClient().send(
            authenticatedRequest(URI("http://localhost:$port/strategies/$strategyId/versions/preview"))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", "test-csrf")
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

        val strategy = authenticatedHttpClient().send(
            authenticatedRequest(URI("http://localhost:$port/strategies/$strategyId")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertTrue(strategy.body().contains("\"versions\":[]"), strategy.body())
    }

    @Test
    fun `previews an incomplete strategy draft without rejecting empty conditions`() {
        seedStrategy(9001L, 2L, "preview fixture", "2099-01-01 00:00:00")

        val response = authenticatedHttpClient().send(
            authenticatedRequest(
                URI("http://localhost:$port/strategies/9001/versions/preview"),
                accessToken = accessToken(memberId = 2L),
            )
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", "test-csrf")
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
        val response = authenticatedHttpClient().send(
            authenticatedRequest(URI("http://localhost:$port/strategies/999999/versions/preview"))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", "test-csrf")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertTrue(response.statusCode() == 404, response.body())
    }

    @Test
    fun `rejects VIX as an execution asset when defining a strategy version`() {
        val created = authenticatedHttpClient().send(
            authenticatedRequest(URI("http://localhost:$port/strategies"))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", "test-csrf")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"volatility hypothesis\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )
        val strategyId = "\"id\":\"(\\d+)\"".toRegex().find(created.body())?.groupValues?.get(1)
        assertTrue(created.statusCode() == 201 && strategyId != null, created.body())

        val response = authenticatedHttpClient().send(
            authenticatedRequest(URI("http://localhost:$port/strategies/$strategyId/versions"))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", "test-csrf")
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
        val response = authenticatedHttpClient().send(
            authenticatedRequest(URI("http://localhost:$port/strategies/999999999999999999")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertTrue(response.statusCode() == 404, response.body())
        assertTrue(response.body().contains("\"code\":\"NOT_FOUND\""), response.body())
        assertTrue(response.body().contains("\"message\":"), response.body())
    }

    @Test
    fun `returns the error response contract for an invalid asset series request`() {
        val response = authenticatedHttpClient().send(
            authenticatedRequest(
                URI("http://localhost:$port/assets/series?symbols=QQQ&metric=INVALID&start=2026-08-03&end=2026-08-25"),
            ).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertEquals(400, response.statusCode(), response.body())
        assertTrue(response.body().contains("\"code\":\"BAD_REQUEST\""), response.body())
        assertTrue(response.body().contains("\"message\":"), response.body())
    }

    @Test
    fun `preserves Compute series data errors through the Core HTTP response`() {
        val response = authenticatedHttpClient().send(
            authenticatedRequest(
                URI("http://localhost:$port/assets/series?symbols=QQQ&metric=PRICE&start=2026-08-03&end=2026-08-25"),
            ).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertEquals(503, response.statusCode(), response.body())
        assertTrue(response.body().contains("\"errorCode\":\"DATASET_CORRUPTION\""), response.body())
        assertTrue(response.body().contains("\"message\":\"Required daily bar is missing.\""), response.body())
    }

    @Test
    fun `creates a pending backtest run through HTTP and persists it`() {
        seedStrategyVersion()
        val response = authenticatedHttpClient().send(
            authenticatedRequest(URI("http://localhost:$port/strategy-versions/42/backtests"))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", "test-csrf")
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
        val created = authenticatedHttpClient().send(
            authenticatedRequest(URI("http://localhost:$port/strategy-versions/42/backtests"))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", "test-csrf")
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

        val polled = authenticatedHttpClient().send(
            authenticatedRequest(URI("http://localhost:$port/backtest-runs/$runId")).GET().build(),
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
        val response = authenticatedHttpClient().send(
            authenticatedRequest(URI("http://localhost:$port/backtest-runs/999999999999999999")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertTrue(response.statusCode() == 404, response.body())
    }

    @Test
    fun `returns a persisted completed backtest result through HTTP`() {
        seedCompletedBacktestRun(99L)

        val response = authenticatedHttpClient().send(
            authenticatedRequest(URI("http://localhost:$port/backtest-runs/99")).GET().build(),
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
        val created = authenticatedHttpClient().send(
            authenticatedRequest(URI("http://localhost:$port/strategy-versions/42/backtests"))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", "test-csrf")
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

        val response = authenticatedHttpClient().send(
            authenticatedRequest(URI("http://localhost:$port/backtest-runs/$runId")).GET().build(),
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

        val firstPage = authenticatedHttpClient().send(
            authenticatedRequest(URI("http://localhost:$port/strategies/777/backtest-runs?page=0&size=1"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertTrue(firstPage.statusCode() == 200, firstPage.body())
        assertTrue(firstPage.body().contains("\"id\":\"7002\""), firstPage.body())
        assertTrue(firstPage.body().contains("\"total\":2"), firstPage.body())
        assertTrue(firstPage.body().contains("\"page\":0"), firstPage.body())
        assertTrue(firstPage.body().contains("\"size\":1"), firstPage.body())

        val secondPage = authenticatedHttpClient().send(
            authenticatedRequest(URI("http://localhost:$port/strategies/777/backtest-runs?page=1&size=1"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertTrue(secondPage.statusCode() == 200, secondPage.body())
        assertTrue(secondPage.body().contains("\"id\":\"7001\""), secondPage.body())

        val missingStrategy = authenticatedHttpClient().send(
            authenticatedRequest(URI("http://localhost:$port/strategies/999999/backtest-runs"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertTrue(missingStrategy.statusCode() == 404, missingStrategy.body())
    }

    @Test
    fun `rejects an invalid backtest period`() {
        val response = authenticatedHttpClient().send(
            authenticatedRequest(URI("http://localhost:$port/strategy-versions/42/backtests"))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", "test-csrf")
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
        val response = authenticatedHttpClient().send(
            authenticatedRequest(URI("http://localhost:$port/strategy-versions/999/backtests"))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", "test-csrf")
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

    private fun authenticatedHttpClient(): HttpClient {
        return HttpClient.newHttpClient()
    }

    private fun authenticatedRequest(uri: URI, accessToken: String = accessToken()): HttpRequest.Builder = HttpRequest.newBuilder(uri)
        .header("Cookie", "REFINVEST_ACCESS_TOKEN=$accessToken; XSRF-TOKEN=test-csrf")

    private fun accessToken(
        memberId: Long = 1,
        audience: String = "refinvest-core-api-test",
        issuedAt: Instant = Instant.now(),
        expiresAt: Instant = Instant.now().plusSeconds(300),
        validate: Boolean = true,
    ): String = jwtEncoder.encode(
            JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder()
                    .issuer("https://test.refinvest.local")
                    .audience(listOf(audience))
                    .subject(memberId.toString())
                    .issuedAt(issuedAt)
                    .expiresAt(expiresAt)
                    .id("test-access-token")
                    .claim("role", "MEMBER")
                    .claim("typ", "access")
                    .build(),
            ),
        ).tokenValue.also { token -> if (validate) jwtDecoder.decode(token) }

    private fun issueRefreshSession(memberId: Long): IssuedAuthenticationTokens {
        jdbcTemplate.update(
            "merge into members (id, role, created_at) key(id) values (?, 'MEMBER', CURRENT_TIMESTAMP)",
            memberId,
        )
        val tokens = authenticationTokenIssuer.issue(MemberId(memberId), "MEMBER", null)
        refreshSessionStore.save(
            RefreshSession(
                jti = tokens.refreshJti,
                memberId = MemberId(memberId),
                familyId = tokens.refreshFamilyId,
                tokenFingerprint = tokens.refreshTokenFingerprint,
                issuedAt = tokens.issuedAt,
                expiresAt = tokens.refreshTokenExpiresAt,
            ),
        )
        return tokens
    }

    private fun refresh(refreshToken: String): HttpResponse<String> = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI("http://localhost:$port/auth/refresh"))
            .header("Cookie", "REFINVEST_REFRESH_TOKEN=$refreshToken; XSRF-TOKEN=test-csrf")
            .header("X-XSRF-TOKEN", "test-csrf")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun refreshSession(jti: java.util.UUID): RefreshSession = RefreshSession(
        jti = jti,
        memberId = MemberId(jdbcTemplate.queryForObject(
            "select member_id from refresh_sessions where jti = ?",
            Long::class.java,
            jti,
        )!!),
        familyId = jdbcTemplate.queryForObject(
            "select family_id from refresh_sessions where jti = ?",
            java.util.UUID::class.java,
            jti,
        )!!,
        tokenFingerprint = jdbcTemplate.queryForObject(
            "select token_fingerprint from refresh_sessions where jti = ?",
            String::class.java,
            jti,
        )!!,
        issuedAt = jdbcTemplate.queryForObject(
            "select issued_at from refresh_sessions where jti = ?",
            Instant::class.java,
            jti,
        )!!,
        expiresAt = jdbcTemplate.queryForObject(
            "select expires_at from refresh_sessions where jti = ?",
            Instant::class.java,
            jti,
        )!!,
        revokedAt = jdbcTemplate.queryForObject(
            "select revoked_at from refresh_sessions where jti = ?",
            Instant::class.java,
            jti,
        ),
    )

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

@TestConfiguration
class AssetDataClientTestConfiguration {
    @Bean
    @Primary
    fun assetDataClient(): AssetDataClient = object : AssetDataClient {
        override fun listAssets(): List<Asset> = emptyList()

        override fun getAvailability(symbol: String): AssetAvailability? = null

        override fun getSeries(query: AssetSeriesQuery): SeriesSnapshot =
            throw SeriesDataErrorException(
                SeriesDataErrorCode.DATASET_CORRUPTION,
                "Required daily bar is missing.",
            )
    }
}
