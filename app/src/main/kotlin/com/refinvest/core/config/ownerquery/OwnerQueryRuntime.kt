package com.refinvest.core.config.ownerquery

import com.refinvest.core.auth.port.inbound.authority.CheckMemberAuthorityUseCase
import com.refinvest.core.auth.port.inbound.authority.MemberAuthorityUnavailableException
import com.refinvest.core.tradingreview.port.common.ownerquery.OwnerMetadataCall
import com.refinvest.core.tradingreview.port.common.ownerquery.OwnerMetadataFailure
import com.refinvest.core.tradingreview.port.common.ownerquery.OwnerMetadataResult
import com.refinvest.core.tradingreview.port.outbound.ownerquery.OwnerMetadataReader
import org.slf4j.LoggerFactory
import org.springframework.boot.web.server.context.WebServerGracefulShutdownLifecycle
import org.springframework.context.SmartLifecycle
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Resource users drain before Boot stops the shared connector, so existing Go callbacks can finish. */
class OwnerQueryRuntime(
    private val prepared: PreparedOwnerQuery,
    private val drainTimeout: Duration,
    private val nanoTime: () -> Long = System::nanoTime,
) : SmartLifecycle, AutoCloseable {
    private enum class State { PREPARED, OPEN, DRAINING_PROXY, AUTHORITY_CLOSED, CLOSED }
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var state = State.PREPARED
    private var reads = 0
    private var checks = 0
    @Volatile var shutdownComplete: Boolean? = null
        private set
    val requestTimeout: Duration = prepared.requestTimeout
    val issuer = prepared.issuer
    val authorityCaller = prepared.authorityCaller

    val reader = OwnerMetadataReader { request, delegation, budget ->
        val admitted = lock.withLock {
            if (state != State.OPEN) false else { reads++; true }
        }
        if (!admitted) {
            OwnerMetadataCall.completed(OwnerMetadataResult.Failure(OwnerMetadataFailure.OWNER_QUERY_UNAVAILABLE))
        } else {
            val finished = AtomicBoolean()
            fun finish() {
                if (finished.compareAndSet(false, true)) lock.withLock { reads--; changed.signalAll() }
            }
            try {
                val call = prepared.reader.read(request, delegation, budget)
                call.result.whenComplete { _, _ -> finish() }
                call
            } catch (error: Exception) {
                finish()
                throw error
            }
        }
    }

    val authority = CheckMemberAuthorityUseCase { command ->
        lock.withLock {
            if (state !in setOf(State.OPEN, State.DRAINING_PROXY)) throw MemberAuthorityUnavailableException()
            checks++
        }
        try {
            prepared.authority.execute(command)
        } finally {
            lock.withLock { checks--; changed.signalAll() }
        }
    }

    init {
        require(drainTimeout > Duration.ZERO && drainTimeout <= Duration.ofSeconds(60)) { "Invalid owner query drain budget" }
    }

    override fun getPhase(): Int = WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE + 1
    override fun isRunning(): Boolean = lock.withLock { state in setOf(State.OPEN, State.DRAINING_PROXY, State.AUTHORITY_CLOSED) }

    override fun start() {
        lock.withLock {
            check(state == State.PREPARED) { "Owner query runtime cannot be restarted" }
            state = State.OPEN
        }
    }

    override fun stop(callback: Runnable) {
        try { close() } finally { callback.run() }
    }

    override fun stop() = close()

    @Synchronized
    override fun close() {
        lock.withLock {
            if (state == State.CLOSED) return
            state = State.DRAINING_PROXY
        }
        val started = nanoTime()
        val limit = drainTimeout.toNanos()
        fun remaining(): Long = (limit - (nanoTime() - started).coerceAtLeast(0)).coerceAtLeast(0)
        fun await(empty: () -> Boolean): Boolean = lock.withLock {
            try {
                while (!empty() && remaining() > 0) changed.awaitNanos(remaining())
                empty()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        }

        val proxyDrained = await { reads == 0 }
        lock.withLock { state = State.AUTHORITY_CLOSED }
        val authorityDrained = await { checks == 0 }
        val clientClosed = try {
            prepared.close(Duration.ofNanos(remaining()))
            true
        } catch (_: Exception) {
            false
        }
        shutdownComplete = proxyDrained && authorityDrained && clientClosed
        lock.withLock { state = State.CLOSED }
        val logger = LoggerFactory.getLogger(OwnerQueryRuntime::class.java)
        if (shutdownComplete == true) {
            logger.info("Owner query shutdown complete")
        } else {
            logger.error("Owner query shutdown incomplete")
        }
    }

    override fun toString() = "OwnerQueryRuntime(redacted)"
}
