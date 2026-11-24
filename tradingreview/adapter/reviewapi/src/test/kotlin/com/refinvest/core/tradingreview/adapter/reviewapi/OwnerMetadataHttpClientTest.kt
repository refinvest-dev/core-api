package com.refinvest.core.tradingreview.adapter.reviewapi

import com.refinvest.core.tradingreview.port.common.ownerquery.*
import com.refinvest.core.tradingreview.port.outbound.ownerquery.*
import com.sun.net.httpserver.HttpExchange
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OwnerMetadataHttpClientTest {
    private lateinit var fixture: TlsFixture
    private val handler = AtomicReference<(HttpExchange) -> Unit>({ error("No test handler installed") })
    private val traceId="12345678901234567890123456789012"
    private val query=OwnerMetadataReader.Request(OwnerMetadataResource.ACCOUNT,"9007199254740993","00-$traceId-1234567890123456-01","vendor=value")
    private val token=OwnerQueryDelegation("header.claims.signature",Instant.now().plusSeconds(60))
    private val credential="A".repeat(43)
    private val body="""{"id":"9007199254740993","venue":"BINANCE","displayName":"account","status":"ACTIVE","createdAt":"2026-11-24T00:00:00Z"}"""

    @BeforeAll
    fun prepare(@TempDir directory: Path) {
        fixture = TlsFixture(TlsFixture.identity(directory)) { handler.get()(it) }
    }

    @AfterAll
    fun close() {
        if (::fixture.isInitialized) fixture.close()
    }

    private fun withHandler(handler: (HttpExchange) -> Unit, test: (TlsFixture) -> Unit) {
        this.handler.set(handler)
        test(fixture)
    }

    private fun client(server:TlsFixture, max:Int=2)=OwnerMetadataHttpClient(OwnerMetadataHttpOptions(server.origin,{credential},
        Duration.ofSeconds(1),Duration.ofSeconds(2),max,listOf(server.certificate)))

    private fun reply(exchange:HttpExchange,status:Int=200,value:String=body){
        exchange.responseHeaders.add("Content-Type","application/json")
        exchange.responseHeaders.add("Cache-Control","no-store")
        val bytes=value.toByteArray()
        exchange.sendResponseHeaders(status,bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    private fun failure(call:OwnerMetadataCall)=assertIs<OwnerMetadataResult.Failure>(call.result.toCompletableFuture().get(5,TimeUnit.SECONDS)).kind

    @Test fun `real TLS rebuilds credentials and preserves bigint`() {
        withHandler({ exchange ->
            assertEquals("/internal/trading-review/v1/accounts/9007199254740993",exchange.requestURI.path)
            assertEquals("Bearer $credential",exchange.requestHeaders.getFirst("Authorization"))
            assertEquals(token.compactToken,exchange.requestHeaders.getFirst("X-RefInvest-User-Delegation"))
            assertNull(exchange.requestHeaders.getFirst("Cookie"))
            assertNull(exchange.requestHeaders.getFirst("Baggage"))
            assertTrue(exchange.requestHeaders.getFirst("traceparent").startsWith("00-$traceId-"))
            reply(exchange)
        }) { server -> client(server).use { transport ->
            val result=assertIs<OwnerMetadataResult.Account>(transport.read(query,token,Duration.ofSeconds(2)).result.toCompletableFuture().get())
            assertEquals(query.resourceId,result.id)
        } }
    }

    @Test fun `registry distinguishes user denial from internal authentication and malformed outcomes`() {
        val selected=AtomicReference(401 to "USER_DELEGATION_INVALID")
        withHandler({ exchange ->
            val(status,code)=selected.get()
            reply(exchange,status,"""{"code":"$code","message":"raw must not be forwarded","traceId":"$traceId","retryable":${status>=500}}""")
        }) { server -> client(server).use { transport ->
        for((status,code,want) in listOf(
            Triple(401,"USER_DELEGATION_INVALID",OwnerMetadataFailure.UPSTREAM_SERVICE_ERROR),
            Triple(403,"CALLER_NOT_ALLOWED",OwnerMetadataFailure.UPSTREAM_SERVICE_ERROR),
            Triple(403,"MEMBER_ACCESS_DENIED",OwnerMetadataFailure.MEMBER_ACCESS_DENIED),
            Triple(404,"RESOURCE_NOT_FOUND",OwnerMetadataFailure.RESOURCE_NOT_FOUND),
            Triple(410,"RESOURCE_DELETED",OwnerMetadataFailure.RESOURCE_DELETED),
            Triple(503,"AUTHORITY_UNAVAILABLE",OwnerMetadataFailure.AUTHORITY_UNAVAILABLE),
            Triple(504,"REQUEST_DEADLINE_EXCEEDED",OwnerMetadataFailure.REQUEST_DEADLINE_EXCEEDED),
            Triple(503,"unknown",OwnerMetadataFailure.UPSTREAM_SERVICE_ERROR))) {
            selected.set(status to code)
            assertEquals(want,failure(transport.read(query,token,Duration.ofSeconds(2))))
        }
        } }
    }

    @Test
    fun `book uses the fixed book path and preserves its parent ID`() {
        val book = query.copy(resource = OwnerMetadataResource.BOOK)
        val parent = "9223372036854775806"
        val response = """{"id":"${book.resourceId}","tradingAccountId":"$parent","venue":"BINANCE","productFamily":"LINEAR_PERPETUAL","positionMode":"ONE_WAY","settlementAsset":"USDT","reviewTimezone":"Asia/Seoul","status":"ARCHIVED","createdAt":"2026-11-24T00:00:00Z"}"""
        withHandler({ exchange ->
            assertEquals("/internal/trading-review/v1/books/${book.resourceId}", exchange.requestURI.path)
            reply(exchange, value = response)
        }) { server ->
            client(server).use { transport ->
                val call = transport.read(book, token, Duration.ofSeconds(2))
                val result = assertIs<OwnerMetadataResult.Book>(call.result.toCompletableFuture().get(5, TimeUnit.SECONDS))
                assertEquals(book.resourceId, result.id)
                assertEquals(parent, result.tradingAccountId)
                assertEquals("ARCHIVED", result.status)
            }
        }
    }

    @Test fun `redirect encoding missing cache malformed and oversized body are unavailable`() {
        val selected=AtomicReference("redirect")
        withHandler({ exchange ->
                val kind=selected.get()
                when(kind){
                    "redirect"->{exchange.responseHeaders.add("Location","https://other.invalid/");reply(exchange,307)}
                    "encoding"->{exchange.responseHeaders.add("Content-Encoding","gzip");reply(exchange)}
                    "cache"->{exchange.responseHeaders.add("Cache-Control","public");reply(exchange)}
                    "duplicate"->reply(exchange,200,body.dropLast(1)+",\"id\":\"9007199254740993\"}")
                    "oversize"->reply(exchange,200,body+" ".repeat(16384))
                    "chunked oversize" -> {
                        exchange.responseHeaders.add("Content-Type", "application/json")
                        exchange.responseHeaders.add("Cache-Control", "no-store")
                        exchange.sendResponseHeaders(200, 0)
                        exchange.responseBody.write((body + " ".repeat(16384)).toByteArray())
                    }
                    "wrong id"->reply(exchange,200,body.replace(query.resourceId,"2"))
                    else->{exchange.responseHeaders.add("Content-Type","application/json");exchange.responseHeaders.add("Cache-Control","no-store");exchange.sendResponseHeaders(200,1);exchange.responseBody.write(byteArrayOf(0xff.toByte()))}
                }
            }) { server -> client(server).use { transport ->
        for(kind in listOf("redirect","encoding","cache","duplicate","oversize","chunked oversize","invalid utf8","wrong id")) {
                selected.set(kind)
                assertEquals(OwnerMetadataFailure.UPSTREAM_SERVICE_ERROR,failure(transport.read(query,token,Duration.ofSeconds(2))),kind)
        }
        } }
    }

    @Test fun `timeout includes slow body and cancellation releases admission`() {
        val entered=CountDownLatch(1)
        val release=CountDownLatch(1)
        withHandler({ exchange ->
            exchange.responseHeaders.add("Content-Type","application/json")
            exchange.responseHeaders.add("Cache-Control","no-store")
            exchange.sendResponseHeaders(200,0)
            exchange.responseBody.write('{'.code)
            exchange.responseBody.flush()
            entered.countDown()
            release.await()
        }) { server -> client(server,1).use { transport ->
            try {
                val first=transport.read(query,token,Duration.ofSeconds(2))
                assertTrue(entered.await(5,TimeUnit.SECONDS))
                assertEquals(OwnerMetadataFailure.OWNER_QUERY_UNAVAILABLE,failure(transport.read(query,token,Duration.ofSeconds(2))))
                assertEquals(OwnerMetadataFailure.REQUEST_DEADLINE_EXCEEDED,failure(first))
                val second=transport.read(query,token,Duration.ofSeconds(2))
                second.cancel()
                assertTrue(second.result.toCompletableFuture().isCancelled)
            } finally { release.countDown() }
        } }
    }

    @Test fun `untrusted CA active transaction and closed handle cannot send successful query`() {
        val requests=AtomicInteger()
        withHandler({ requests.incrementAndGet(); reply(it) }) { server ->
            OwnerMetadataHttpClient(OwnerMetadataHttpOptions(server.origin,{credential},Duration.ofSeconds(1),Duration.ofSeconds(2),1)).use {
                assertEquals(OwnerMetadataFailure.UPSTREAM_SERVICE_ERROR,failure(it.read(query,token,Duration.ofSeconds(2))))
            }
            client(server).use { transport ->
                TransactionSynchronizationManager.setActualTransactionActive(true)
                try { assertEquals(OwnerMetadataFailure.OWNER_QUERY_UNAVAILABLE,failure(transport.read(query,token,Duration.ofSeconds(2)))) }
                finally { TransactionSynchronizationManager.clear() }
                transport.close()
                assertEquals(OwnerMetadataFailure.OWNER_QUERY_UNAVAILABLE,failure(transport.read(query,token,Duration.ofSeconds(2))))
            }
            assertEquals(0,requests.get())
        }
    }
}
