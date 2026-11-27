package com.refinvest.core.tradingreview.adapter.reviewapi

import com.refinvest.core.tradingreview.port.common.ownerquery.OwnerMetadataFailure
import com.refinvest.core.tradingreview.port.common.ownerquery.OwnerMetadataResource
import com.refinvest.core.tradingreview.port.common.ownerquery.OwnerMetadataResult
import com.refinvest.core.tradingreview.port.outbound.ownerquery.OwnerMetadataReader
import com.refinvest.core.tradingreview.port.outbound.ownerquery.OwnerQueryDelegation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.URI
import java.nio.file.Path
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OwnerMetadataHeaderCancellationTest {
    @Test
    fun `header rejection closes its TLS exchange before response EOF and permits another request`(@TempDir directory: Path) {
        val identity = TlsFixture.identity(directory)
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(identity, "fixture-only".toCharArray())
        }
        val context = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
        val listener = context.serverSocketFactory.createServerSocket() as SSLServerSocket
        val socket = AtomicReference<SSLSocket?>()
        val peerClosed = CountDownLatch(1)
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val id = "9007199254740993"
        val trace = "00-12345678901234567890123456789012-1234567890123456-01"
        val query = OwnerMetadataReader.Request(OwnerMetadataResource.ACCOUNT, id, trace, null)
        val token = OwnerQueryDelegation("header.claims.signature", Instant.now().plusSeconds(60))
        val body = """{"id":"$id","venue":"BINANCE","displayName":"account","status":"ACTIVE","createdAt":"2026-11-24T00:00:00Z"}"""
        try {
            listener.bind(InetSocketAddress("127.0.0.1", 0))
            listener.soTimeout = 5000
            val server = executor.submit {
                (listener.accept() as SSLSocket).use { first ->
                    socket.set(first)
                    first.soTimeout = 5000
                    headers(first)
                    first.outputStream.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                        "Cache-Control: no-store\r\nContent-Length: 16385\r\nConnection: close\r\n\r\n{").toByteArray())
                    first.outputStream.flush()
                    try {
                        assertEquals(-1, first.inputStream.read())
                    } catch (_: SocketException) {
                        // A reset is also physical peer cancellation; a read timeout is not accepted.
                    }
                    peerClosed.countDown()
                }
                (listener.accept() as SSLSocket).use { second ->
                    socket.set(second)
                    second.soTimeout = 5000
                    headers(second)
                    val payload = body.toByteArray()
                    second.outputStream.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                        "Cache-Control: no-store\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n").toByteArray())
                    second.outputStream.write(payload)
                    second.outputStream.flush()
                }
            }
            val options = OwnerMetadataHttpOptions(URI("https://127.0.0.1:${listener.localPort}"), { "A".repeat(43) },
                Duration.ofSeconds(1), Duration.ofSeconds(2), 1, listOf(identity.getCertificate("fixture") as X509Certificate))
            OwnerMetadataHttpClient(options).use { client ->
                val rejected = client.read(query, token, Duration.ofSeconds(3)).result.toCompletableFuture().get(5, TimeUnit.SECONDS)
                assertEquals(OwnerMetadataFailure.UPSTREAM_SERVICE_ERROR, assertIs<OwnerMetadataResult.Failure>(rejected).kind)
                assertTrue(peerClosed.await(2, TimeUnit.SECONDS), "Header rejection left the body exchange open")
                val accepted = client.read(query, token, Duration.ofSeconds(3)).result.toCompletableFuture().get(5, TimeUnit.SECONDS)
                assertEquals(id, assertIs<OwnerMetadataResult.Account>(accepted).id)
            }
            server.get(5, TimeUnit.SECONDS)
        } finally {
            socket.get()?.close()
            listener.close()
            executor.shutdownNow()
            executor.close()
        }
    }

    private fun headers(socket: SSLSocket) {
        var tail = ""
        repeat(16 * 1024) {
            val value = socket.inputStream.read()
            check(value >= 0) { "Fixture request ended before headers" }
            tail = (tail + value.toChar()).takeLast(4)
            if (tail == "\r\n\r\n") return
        }
        error("Fixture request header budget exceeded")
    }
}
