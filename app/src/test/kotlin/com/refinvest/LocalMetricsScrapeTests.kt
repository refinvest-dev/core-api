package com.refinvest

import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("dev-observability")
@Import(AssetDataClientTestConfiguration::class)
@TestPropertySource(properties = [
    "spring.datasource.url=jdbc:h2:mem:metrics;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "REFINVEST_METRICS_SCRAPE_PASSWORD=test-only-scrape-password-for-tests",
])
class LocalMetricsScrapeTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val meterRegistry: MeterRegistry,
    @LocalServerPort private val port: Int,
) {
    @Test
    fun `unauthenticated and incorrect credentials cannot scrape`() {
        assertEquals(401, scrape().statusCode())
        assertEquals(401, scrape("wrong-password").statusCode())
    }

    @Test
    fun `dedicated credential returns existing Core metrics on repeated scrapes`() {
        meterRegistry.counter("refinvest.backtest.submissions").increment()
        repeat(3) {
            val response = scrape("test-only-scrape-password-for-tests")
            assertEquals(200, response.statusCode())
            assertTrue(response.body().contains("jvm_memory_used_bytes"), response.body())
            assertTrue(response.body().contains("refinvest_backtest_submissions_total"), response.body())
            assertTrue(!response.body().contains("test-only-scrape-password-for-tests"))
        }
    }

    @Test
    fun `scrape credential cannot access user APIs or scrape from a non-loopback peer`() {
        mockMvc.perform(get("/actuator/prometheus")
            .with { request -> request.remoteAddr = "192.0.2.1"; request }
            .header("Authorization", basicHeader("test-only-scrape-password-for-tests")))
            .andExpect(status().isForbidden)

        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/strategies"))
            .header("Authorization", basicHeader("test-only-scrape-password-for-tests"))
            .GET().build()
        assertEquals(401, HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
    }

    private fun scrape(password: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/actuator/prometheus"))
        if (password != null) builder.header("Authorization", basicHeader(password))
        return HttpClient.newHttpClient().send(builder.GET().build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun basicHeader(password: String): String = "Basic " + Base64.getEncoder()
        .encodeToString("prometheus:$password".toByteArray(StandardCharsets.UTF_8))
}
