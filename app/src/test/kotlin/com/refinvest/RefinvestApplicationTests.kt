package com.refinvest

import com.refinvest.core.strategy.port.outbound.StrategyIdGenerator
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
    fun `returns not found for an unknown strategy`() {
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://localhost:$port/strategies/999999999999999999")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertTrue(response.statusCode() == 404, response.body())
    }

}
