package com.refinvest.core.config.ownerquery

import com.refinvest.core.auth.port.inbound.authority.*
import com.refinvest.core.auth.port.inbound.delegation.*
import com.refinvest.core.tradingreview.port.common.ownerquery.*
import com.refinvest.core.tradingreview.port.outbound.ownerquery.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.boot.web.server.context.WebServerGracefulShutdownLifecycle
import org.springframework.context.SmartLifecycle
import org.springframework.context.support.GenericApplicationContext
import org.springframework.beans.factory.config.BeanDefinitionCustomizer
import java.time.Duration
import java.time.Instant
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier
import kotlin.test.*

class OwnerQueryRuntimeTest {
    private val request = OwnerMetadataReader.Request(OwnerMetadataResource.ACCOUNT, "1",
        "00-12345678901234567890123456789012-1234567890123456-01", null)
    private val delegation = OwnerQueryDelegation("synthetic", Instant.now().plusSeconds(60))
    private val command = CheckMemberAuthorityCommand("synthetic", "core-api", "a".repeat(32))
    private val response = mock(MemberAuthorityResult::class.java)

    private fun prepared(
        reader: OwnerMetadataReader = OwnerMetadataReader { _, _, _ -> OwnerMetadataCall.completed(OwnerMetadataResult.Failure(OwnerMetadataFailure.RESOURCE_NOT_FOUND)) },
        check: CheckMemberAuthorityUseCase = CheckMemberAuthorityUseCase { response },
        close: (Duration?) -> Unit = {},
    ) = PreparedOwnerQuery(IssueOwnerQueryDelegationUseCase { mock(OwnerQueryDelegationResult::class.java) }, check,
        AuthenticateAuthorityCallerUseCase { true }, reader, Duration.ofSeconds(5), close)

    private fun read(runtime: OwnerQueryRuntime) = runtime.reader.read(request, delegation, Duration.ofSeconds(5))

    @Test
    fun `start admission close and non-restartability have explicit states`() {
        val closed = AtomicInteger()
        val runtime = OwnerQueryRuntime(prepared(close = { closed.incrementAndGet() }), Duration.ofSeconds(1))
        assertEquals(OwnerMetadataFailure.OWNER_QUERY_UNAVAILABLE, assertIs<OwnerMetadataResult.Failure>(read(runtime).result.toCompletableFuture().join()).kind)
        assertFailsWith<MemberAuthorityUnavailableException> { runtime.authority.execute(command) }
        runtime.start()
        assertTrue(runtime.isRunning)
        assertEquals(OwnerMetadataFailure.RESOURCE_NOT_FOUND, assertIs<OwnerMetadataResult.Failure>(read(runtime).result.toCompletableFuture().join()).kind)
        assertSame(response, runtime.authority.execute(command))
        runtime.close()
        runtime.close()
        assertEquals(1, closed.get())
        assertEquals(true, runtime.shutdownComplete)
        assertFalse(runtime.isRunning)
        assertFails { runtime.start() }
        assertTrue(runtime.phase > WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE)
    }

    @Test
    fun `proxy drain denies new reads while allowing its authority callback`() {
        val result = CompletableFuture<OwnerMetadataResult>()
        val draining = CountDownLatch(1)
        val runtime = OwnerQueryRuntime(prepared(OwnerMetadataReader { _, _, _ -> OwnerMetadataCall(result, Duration.ofSeconds(5)) {} }),
            Duration.ofSeconds(2)) { draining.countDown(); System.nanoTime() }
        runtime.start()
        read(runtime)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val stopped = executor.submit { runtime.close() }
            assertTrue(draining.await(1, TimeUnit.SECONDS))
            assertEquals(OwnerMetadataFailure.OWNER_QUERY_UNAVAILABLE, assertIs<OwnerMetadataResult.Failure>(read(runtime).result.toCompletableFuture().join()).kind)
            assertSame(response, runtime.authority.execute(command))
            result.complete(OwnerMetadataResult.Failure(OwnerMetadataFailure.RESOURCE_NOT_FOUND))
            stopped.get(3, TimeUnit.SECONDS)
            assertEquals(true, runtime.shutdownComplete)
            assertFailsWith<MemberAuthorityUnavailableException> { runtime.authority.execute(command) }
        } finally {
            result.complete(OwnerMetadataResult.Failure(OwnerMetadataFailure.OWNER_QUERY_UNAVAILABLE))
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(4, TimeUnit.SECONDS))
            runtime.close()
        }
    }

    @Test
    fun `authority users finish before the owned client closes`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = AtomicInteger()
        val runtime = OwnerQueryRuntime(prepared(check = CheckMemberAuthorityUseCase {
            entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            returned.incrementAndGet()
            response
        }, close = { assertEquals(1, returned.get()) }), Duration.ofSeconds(2))
        val executor = Executors.newFixedThreadPool(2)
        runtime.start()
        try {
            val query = executor.submit<MemberAuthorityResult> { runtime.authority.execute(command) }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val stopped = executor.submit { runtime.close() }
            assertFailsWith<TimeoutException> { stopped.get(50, TimeUnit.MILLISECONDS) }
            release.countDown()
            query.get(2, TimeUnit.SECONDS)
            stopped.get(2, TimeUnit.SECONDS)
            assertEquals(true, runtime.shutdownComplete)
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(4, TimeUnit.SECONDS))
            runtime.close()
        }
    }

    @Test
    fun `expired drain forces cancellation and reports incomplete without granting a fresh close budget`() {
        val pending = CompletableFuture<OwnerMetadataResult>()
        var budget: Duration? = null
        val runtime = OwnerQueryRuntime(prepared(OwnerMetadataReader { _, _, _ -> OwnerMetadataCall(pending, Duration.ofSeconds(5)) {} }, close = {
            budget = it
            pending.complete(OwnerMetadataResult.Failure(OwnerMetadataFailure.OWNER_QUERY_UNAVAILABLE))
        }), Duration.ofMillis(10))
        runtime.start()
        read(runtime)
        runtime.close()
        assertEquals(Duration.ZERO, budget)
        assertEquals(false, runtime.shutdownComplete)
        assertFalse(runtime.isRunning)
    }

    @Test
    fun `uncooperative authority is reported as incomplete rather than a successful join`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val runtime = OwnerQueryRuntime(prepared(check = CheckMemberAuthorityUseCase {
            entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            response
        }), Duration.ofMillis(10))
        val executor = Executors.newSingleThreadExecutor()
        runtime.start()
        try {
            val query = executor.submit<MemberAuthorityResult> { runtime.authority.execute(command) }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            runtime.close()
            assertEquals(false, runtime.shutdownComplete)
            assertFalse(query.isDone)
            release.countDown()
            query.get(2, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(4, TimeUnit.SECONDS))
            runtime.close()
        }
    }

    @Test
    fun `cleanup failure preserves interruption and always completes the lifecycle callback`() {
        val callback = AtomicInteger()
        val runtime = OwnerQueryRuntime(prepared(close = { throw InterruptedException("synthetic") }), Duration.ofSeconds(1))
        runtime.start()
        try {
            runtime.stop { callback.incrementAndGet() }
            assertEquals(1, callback.get())
            assertEquals(false, runtime.shutdownComplete)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `invalid close budget does not consume the prepared owner`() {
        val closed = AtomicInteger()
        val prepared = prepared(close = { closed.incrementAndGet() })
        assertFailsWith<OwnerQueryPreparationException> { prepared.close(Duration.ofSeconds(-1)) }
        prepared.close()
        assertEquals(1, closed.get())
    }

    @Test
    fun `Spring lifecycle drains the runtime before connector stop and resource destruction`() {
        val events = mutableListOf<String>()
        GenericApplicationContext().use { context ->
            context.registerBean("resource", AutoCloseable::class.java, Supplier { AutoCloseable { events.add("resource") } },
                BeanDefinitionCustomizer { it.setDestroyMethodName("close") })
            context.registerBean("runtime", OwnerQueryRuntime::class.java, Supplier {
                OwnerQueryRuntime(prepared(close = { events.add("client") }), Duration.ofSeconds(1))
            }, BeanDefinitionCustomizer { it.setDependsOn("resource"); it.setDestroyMethodName("close") })
            context.registerBean("connector", SmartLifecycle::class.java, Supplier { object : SmartLifecycle {
                private var running = false
                override fun getPhase() = WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE
                override fun start() { running = true }
                override fun isRunning() = running
                override fun stop() { running = false; events.add("connector") }
            } })
            context.refresh()
        }
        assertEquals(listOf("client", "connector", "resource"), events)
    }
}
