package com.refinvest

import com.refinvest.core.asset.adapter.out.compute.RestClientAssetDataClient
import com.refinvest.core.backtest.adapter.out.compute.RestClientComputeBacktestStatusClient
import com.refinvest.core.backtest.adapter.out.compute.RestClientComputeClient
import com.refinvest.core.backtest.domain.valueobject.*
import com.refinvest.core.backtest.port.outbound.compute.*
import com.refinvest.core.shared.infrastructure.http.HttpClientTimeouts
import com.sun.net.httpserver.HttpServer
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.core.convert.support.DefaultConversionService
import org.springframework.core.env.MapPropertySource
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.http.HttpConnectTimeoutException
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ComputeHttpTimeoutTest {
    @Test
    fun submitTimesOutBeforeHeadersAndDuringBodyDelivery() {
        for (partialBody in listOf(false, true)) withServer(partialBody, 202) { server ->
            clients(server.url).use { context ->
                val result = bounded(server) { context.getBean(RestClientComputeClient::class.java).requestBacktest(request()) }
                assertEquals(Duration.ofSeconds(10), assertIs<ComputeBacktestSubmission.RetryLater>(result).retryAfter)
            }
        }
    }

    @Test
    fun pollingTimesOutBeforeHeadersAndDuringBodyDelivery() {
        for (partialBody in listOf(false, true)) withServer(partialBody) { server ->
            clients(server.url).use { context ->
                val result = bounded(server) {
                    context.getBean(RestClientComputeBacktestStatusClient::class.java)
                        .getBacktestStatus("synthetic-job", BacktestRunId(1))
                }
                assertEquals(Duration.ofSeconds(10), assertIs<ComputeBacktestStatusLookup.RetryLater>(result).retryAfter)
            }
        }
    }

    @Test
    fun bodyProgressCannotExtendThePollingDeadline(): Unit = withServer(true, progress = true) { server ->
        clients(server.url).use { context ->
            assertIs<ComputeBacktestStatusLookup.RetryLater>(bounded(server) {
                context.getBean(RestClientComputeBacktestStatusClient::class.java)
                    .getBacktestStatus("synthetic-job", BacktestRunId(1))
            })
        }
    }

    @Test
    fun everyAssetReadTimesOutBeforeHeadersAndDuringBodyDelivery() {
        for (partialBody in listOf(false, true)) for (operation in 0..2) withServer(partialBody) { server ->
            clients(server.url).use { context ->
                val client = context.getBean(RestClientAssetDataClient::class.java)
                val failure = bounded(server) {
                    runCatching {
                        when (operation) {
                            0 -> client.listAssets()
                            1 -> client.getAvailability("QQQ")
                            else -> client.getSeries(com.refinvest.core.asset.port.outbound.compute.AssetSeriesQuery(
                                listOf("QQQ"), com.refinvest.core.asset.port.outbound.compute.SeriesMetric.PRICE,
                                LocalDate.parse("2025-01-01"), LocalDate.parse("2025-01-03"), null))
                        }
                    }.exceptionOrNull()
                }
                assertNotNull(failure)
                assertTrue(generateSequence(failure) { it.cause }.any { it is java.io.IOException })
            }
        }
    }

    @Test
    fun refusedConnectionIsTransientForSubmitAndPoll() {
        val port = ServerSocket(0).use { it.localPort }
        clients("http://127.0.0.1:$port").use { context ->
            assertIs<ComputeBacktestSubmission.RetryLater>(context.getBean(RestClientComputeClient::class.java).requestBacktest(request()))
            assertIs<ComputeBacktestStatusLookup.RetryLater>(context.getBean(RestClientComputeBacktestStatusClient::class.java)
                .getBacktestStatus("synthetic-job", BacktestRunId(1)))
        }
    }

    @Test
    fun connectTimeoutBoundsAStalledTlsHandshake() {
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { socket ->
            val accepted = CountDownLatch(1)
            val release = CountDownLatch(1)
            val pool = Executors.newVirtualThreadPerTaskExecutor()
            try {
                val peer = pool.submit {
                    socket.accept().use { accepted.countDown(); release.await(5, TimeUnit.SECONDS) }
                }
                val attempt = pool.submit<Throwable?> {
                    runCatching {
                        RestClient.builder().requestFactory(HttpClientTimeouts(Duration.ofMillis(200), Duration.ofSeconds(4)).requestFactory())
                            .build().get().uri("https://127.0.0.1:${socket.localPort}/").retrieve().toBodilessEntity()
                    }.exceptionOrNull()
                }
                assertTrue(accepted.await(5, TimeUnit.SECONDS))
                val failure = assertIs<ResourceAccessException>(attempt.get(3, TimeUnit.SECONDS))
                assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it is HttpConnectTimeoutException })
                release.countDown()
                peer.get(3, TimeUnit.SECONDS)
            } finally { release.countDown(); pool.shutdownNow() }
        }
    }

    @Test
    fun invalidTimeoutsAndLeaseSizedBudgetsFailClientCreation() {
        for ((connect, read) in listOf("PT0S" to "PT1S", "PT1S" to "PT0S", "-PT1S" to "PT1S",
            "PT1S" to "-PT1S", "PT1S" to "PT59S", "PT2S" to "PT59S")) {
            assertFails { clients("http://127.0.0.1:1", connect, read).close() }
        }
        clients("http://127.0.0.1:1", "PT2S", "PT20S").close()
    }

    private fun clients(url: String, connect: String = "PT1S", read: String = "PT0.25S"): AnnotationConfigApplicationContext {
        val context = AnnotationConfigApplicationContext()
        context.environment.propertySources.addFirst(MapPropertySource("timeout-test", mapOf(
            "refinvest.compute.base-url" to url, "refinvest.compute.api-key" to "synthetic-key",
            "refinvest.compute.connect-timeout" to connect, "refinvest.compute.read-timeout" to read)))
        context.beanFactory.conversionService = DefaultConversionService().apply {
            addConverter(String::class.java, Duration::class.java) { Duration.parse(it) }
        }
        context.registerBean(MeterRegistry::class.java, java.util.function.Supplier { SimpleMeterRegistry() })
        context.registerBean(ObjectMapper::class.java, java.util.function.Supplier { jacksonObjectMapper() })
        context.register(RestClientComputeClient::class.java, RestClientComputeBacktestStatusClient::class.java, RestClientAssetDataClient::class.java)
        try { context.refresh(); return context } catch (failure: Throwable) { context.close(); throw failure }
    }

    private class DelayedServer(val server: HttpServer, val entered: CountDownLatch, val release: CountDownLatch) {
        val url = "http://127.0.0.1:${server.address.port}"
    }

    private fun <T> withServer(partialBody: Boolean, status: Int = 200, progress: Boolean = false, test: (DelayedServer) -> T): T {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                exchange.requestBody.readAllBytes()
                if (partialBody) {
                    exchange.responseHeaders.set("Content-Type", "application/json")
                    exchange.sendResponseHeaders(status, if (progress) 1_000_000 else 100)
                    exchange.responseBody.write('{'.code)
                    exchange.responseBody.flush()
                }
                entered.countDown()
                if (progress) {
                    while (!release.await(20, TimeUnit.MILLISECONDS)) {
                        exchange.responseBody.write(' '.code)
                        exchange.responseBody.flush()
                    }
                } else release.await(10, TimeUnit.SECONDS)
            } finally { exchange.close() }
        }
        server.start()
        try { return test(DelayedServer(server, entered, release)) }
        finally { release.countDown(); server.stop(0); executor.shutdownNow() }
    }

    private fun <T> bounded(server: DelayedServer, call: () -> T): T {
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        try {
            val future = executor.submit<T> { call() }
            assertTrue(server.entered.await(5, TimeUnit.SECONDS), "The real HTTP request must reach the server")
            return future.get(3, TimeUnit.SECONDS)
        } finally { server.release.countDown(); executor.shutdownNow() }
    }

    private fun request() = ComputeBacktestRequest(ComputeIdempotencyKey(UUID.randomUUID()), StrategyVersionId(12),
        StrategyVersionPayload("QQQ", listOf(ComputeConditionPayload("GT", null,
            MetricReferencePayload("QQQ", "SIMPLE", null), LiteralOperandPayload(0.0))), "QQQ", 0,
            TimeBasedExitPayload(1), PositionPolicyPayload(true, true, "IGNORE")),
        FeeModel(Percent(BigDecimal.ZERO), Percent(BigDecimal.ZERO)),
        Period(LocalDate.parse("2025-01-01"), LocalDate.parse("2025-01-03")))
}
