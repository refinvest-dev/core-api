package com.refinvest.core.tradingreview.adapter.reviewapi

import com.refinvest.core.shared.infrastructure.http.TraceContextHeaders
import com.refinvest.core.tradingreview.port.common.ownerquery.OwnerMetadataCall
import com.refinvest.core.tradingreview.port.common.ownerquery.OwnerMetadataFailure
import com.refinvest.core.tradingreview.port.common.ownerquery.OwnerMetadataResource
import com.refinvest.core.tradingreview.port.common.ownerquery.OwnerMetadataResult
import com.refinvest.core.tradingreview.port.outbound.ownerquery.OwnerMetadataReader
import com.refinvest.core.tradingreview.port.outbound.ownerquery.OwnerQueryDelegation
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class OwnerMetadataHttpClient(options: OwnerMetadataHttpOptions) : OwnerMetadataReader, AutoCloseable {
    private val origin = options.origin
    private val credential = options.credential
    private val forbidden = options.forbiddenCredentials.toSet()
    private val shutdownBudget = options.shutdownTimeout
    private val slots = Semaphore(options.maxInFlight)
    private val closed = AtomicBoolean()
    private val active = ConcurrentHashMap<CompletableFuture<OwnerMetadataResult>, Pending>()
    private val timer = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "owner-query-deadline").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private val client: HttpClient
    private val decoder = OwnerMetadataResponseDecoder()

    init {
        options.validate()
        client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(options.connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .proxy(object : ProxySelector() {
                override fun select(uri: URI): List<Proxy> = listOf(Proxy.NO_PROXY)
                override fun connectFailed(uri: URI, address: SocketAddress, error: java.io.IOException) = Unit
            })
            .sslContext(options.trustContext())
            .sslParameters(javax.net.ssl.SSLParameters().apply {
                endpointIdentificationAlgorithm = "HTTPS"
                protocols = arrayOf("TLSv1.3", "TLSv1.2")
            })
            .build()
    }

    override fun read(
        query: OwnerMetadataReader.Request,
        delegation: OwnerQueryDelegation,
        budget: Duration,
    ): OwnerMetadataCall {
        val started = System.nanoTime()
        if (closed.get() || TransactionSynchronizationManager.isActualTransactionActive()) {
            return completed(OwnerMetadataFailure.OWNER_QUERY_UNAVAILABLE)
        }

        val id = query.resourceId.toLongOrNull()
        if (id == null || id <= 0 || id.toString() != query.resourceId || budget <= Duration.ZERO || budget > Duration.ofSeconds(60)) {
            return completed(OwnerMetadataFailure.INVALID_REQUEST)
        }

        val request = try {
            val secret = credential()
            if (!credentialPattern.matches(secret) || secret in forbidden || delegation.compactToken.length !in 1..4096 ||
                !compactPattern.matches(delegation.compactToken)
            ) {
                return completed(OwnerMetadataFailure.UPSTREAM_SERVICE_ERROR)
            }

            val trace = TraceContextHeaders.child(query.traceParent, query.traceState)
            val resource = if (query.resource == OwnerMetadataResource.ACCOUNT) "accounts" else "books"
            val builder = HttpRequest.newBuilder(origin.resolve("/internal/trading-review/v1/$resource/${query.resourceId}"))
                .timeout(budget)
                .GET()
                .header("Authorization", "Bearer $secret")
                .header("X-RefInvest-User-Delegation", delegation.compactToken)
                .header("Accept", "application/json")
                .header("Cache-Control", "no-store")
                .header("traceparent", trace.parent)
            trace.state?.let { builder.header("tracestate", it) }
            builder.build() to trace.traceId
        } catch (_: Exception) {
            return completed(OwnerMetadataFailure.UPSTREAM_SERVICE_ERROR)
        }

        val remaining = budget.minusNanos((System.nanoTime() - started).coerceAtLeast(0))
        if (remaining <= Duration.ZERO) return completed(OwnerMetadataFailure.REQUEST_DEADLINE_EXCEEDED)
        if (!slots.tryAcquire()) return completed(OwnerMetadataFailure.OWNER_QUERY_UNAVAILABLE)

        val result = CompletableFuture<OwnerMetadataResult>()
        val pending = Pending()
        active[result] = pending
        result.whenComplete { _, _ -> if (result.isCancelled) finish(result, pending, cancelled = true) }

        try {
            if (closed.get()) {
                finish(result, pending, OwnerMetadataResult.Failure(OwnerMetadataFailure.OWNER_QUERY_UNAVAILABLE))
            } else {
                val deadline = timer.schedule(
                    { finish(result, pending, OwnerMetadataResult.Failure(OwnerMetadataFailure.REQUEST_DEADLINE_EXCEEDED)) },
                    remaining.toNanos(),
                    TimeUnit.NANOSECONDS,
                )
                pending.deadline.set(deadline)
                if (pending.finished.get()) deadline.cancel(false)

                if (!pending.finished.get()) {
                    val network = client.sendAsync(request.first) { info ->
                        decoder.checkHeaders(info)
                        HttpResponse.BodySubscribers.limiting(HttpResponse.BodySubscribers.ofByteArray(), 16L shl 10)
                    }
                    pending.network.set(network)
                    if (pending.finished.get()) network.cancel(true)

                    network.whenComplete { response, error ->
                        val outcome = if (error != null) {
                            val cause = if (error is CompletionException) error.cause else error
                            val kind = if (cause is HttpTimeoutException) {
                                OwnerMetadataFailure.REQUEST_DEADLINE_EXCEEDED
                            } else {
                                OwnerMetadataFailure.UPSTREAM_SERVICE_ERROR
                            }
                            OwnerMetadataResult.Failure(kind)
                        } else {
                            decoder.decode(response.statusCode(), response.body(), query, request.second)
                        }
                        finish(result, pending, outcome)
                    }
                }
            }
        } catch (_: Exception) {
            finish(result, pending, OwnerMetadataResult.Failure(OwnerMetadataFailure.OWNER_QUERY_UNAVAILABLE))
        }

        return OwnerMetadataCall(result, remaining) { finish(result, pending, cancelled = true) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        active.forEach { (result, pending) ->
            finish(result, pending, OwnerMetadataResult.Failure(OwnerMetadataFailure.OWNER_QUERY_UNAVAILABLE))
        }

        client.shutdownNow()
        timer.shutdownNow()
        check(client.awaitTermination(shutdownBudget) && timer.awaitTermination(shutdownBudget.toNanos(), TimeUnit.NANOSECONDS)) {
            "Owner metadata client did not terminate"
        }
    }

    private fun completed(kind: OwnerMetadataFailure) = OwnerMetadataCall.completed(OwnerMetadataResult.Failure(kind))

    // Release logical ownership before making completion visible to a waiting caller.
    private fun finish(
        result: CompletableFuture<OwnerMetadataResult>,
        pending: Pending,
        outcome: OwnerMetadataResult? = null,
        cancelled: Boolean = false,
    ) {
        if (!pending.finished.compareAndSet(false, true)) return

        pending.deadline.get()?.cancel(false)
        pending.network.get()?.takeIf { !it.isDone }?.cancel(true)
        active.remove(result)
        slots.release()

        if (cancelled) result.cancel(true) else result.complete(requireNotNull(outcome))
    }

    private class Pending {
        val finished = AtomicBoolean()
        val network = AtomicReference<CompletableFuture<HttpResponse<ByteArray>>?>()
        val deadline = AtomicReference<ScheduledFuture<*>?>()
    }

    private companion object {
        val credentialPattern = Regex("[A-Za-z0-9_-]{43,128}")
        val compactPattern = Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
    }
}
