package com.refinvest.core.tradingreview.application

import com.refinvest.core.tradingreview.application.artifactgrant.redeem.RedeemTradingArtifactGrantService
import com.refinvest.core.tradingreview.domain.valueobject.TradingSourceArtifactId
import com.refinvest.core.tradingreview.port.common.failure.TradingFailure
import com.refinvest.core.tradingreview.port.common.failure.TradingFailureKind
import com.refinvest.core.tradingreview.port.inbound.artifactgrant.redeem.RedeemTradingArtifactGrantCommand
import com.refinvest.core.tradingreview.port.outbound.artifactgrant.ArtifactGrantTokens
import com.refinvest.core.tradingreview.port.outbound.persistence.artifactgrant.ArtifactGrantConsumption
import com.refinvest.core.tradingreview.port.outbound.persistence.artifactgrant.ArtifactGrantReadSource
import com.refinvest.core.tradingreview.port.outbound.persistence.artifactgrant.ArtifactGrantRedemptionStore
import com.refinvest.core.tradingreview.port.outbound.storage.raw.RawArtifactStorage
import java.io.InputStream
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.springframework.transaction.support.TransactionSynchronizationManager

class RedeemTradingArtifactGrantServiceTest {
    private val now = Instant.parse("2026-06-01T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `redemption verifies then consumes and transfers stream ownership without reading`() {
        val token = "synthetic.opaque-token"
        val command = RedeemTradingArtifactGrantCommand(token)
        val bytes = "synthetic raw".toByteArray()
        val delegate = bytes.inputStream()
        var reads = 0
        var closed = false
        val calls = mutableListOf<String>()
        val stream = object : InputStream() {
            override fun read(): Int { reads++; return delegate.read() }
            override fun close() { closed = true; delegate.close() }
        }
        val tokens = tokens { supplied ->
            assertEquals(token, supplied)
            calls += "verify"
            "a".repeat(64)
        }
        val source = ArtifactGrantReadSource(TradingSourceArtifactId(51), "objects/synthetic", byteArrayOf(1),
            "test-key", bytes.size.toLong(), "text/csv")
        val leaked = source.wrappedDek
        leaked[0] = 9
        assertTrue(source.wrappedDek.contentEquals(byteArrayOf(1)))
        val store = port<ArtifactGrantRedemptionStore> { name, args ->
            assertEquals("consume", name)
            assertEquals(ArtifactGrantConsumption("a".repeat(64), now), args[0])
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
            calls += "consume"
            source
        }
        val storage = port<RawArtifactStorage> { name, args ->
            assertEquals("open", name)
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
            assertEquals("objects/synthetic", args[0])
            assertTrue((args[1] as ByteArray).contentEquals(byteArrayOf(1)))
            calls += "open"
            stream
        }
        val result = RedeemTradingArtifactGrantService(tokens, store, storage, clock).execute(command)
        assertEquals(listOf("verify", "consume", "open"), calls)
        assertFalse(command.toString().contains(token))
        assertSame(stream, result.input)
        assertEquals(bytes.size.toLong(), result.contentLength)
        assertEquals("text/csv", result.mediaType)
        assertEquals(0, reads)
        assertFalse(closed)
        assertTrue(result.input.use { it.readAllBytes() }.contentEquals(bytes))
        assertTrue(closed)
    }

    @Test
    fun `rejected consumption keeps the original safe failure and does not retry or open storage`() {
        val failure = TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)
        var calls = 0
        val store = port<ArtifactGrantRedemptionStore> { name, _ ->
            assertEquals("consume", name)
            calls++
            throw failure
        }
        val service = RedeemTradingArtifactGrantService(tokens { "a".repeat(64) }, store, port(), clock)
        assertSame(failure, assertFailsWith<TradingFailure> {
            service.execute(RedeemTradingArtifactGrantCommand("synthetic.rejected-token"))
        })
        assertEquals(1, calls)
    }

    @Test
    fun `invalid signature is rejected without touching persistence or storage`() {
        val service = RedeemTradingArtifactGrantService(tokens { null }, port(), port(), clock)
        val failure = assertFailsWith<TradingFailure> {
            service.execute(RedeemTradingArtifactGrantCommand("synthetic.invalid-token"))
        }
        assertEquals("RESOURCE_NOT_FOUND", failure.code)
        assertEquals(TradingFailureKind.NOT_FOUND, failure.kind)
    }

    private fun tokens(verify: (String) -> String?): ArtifactGrantTokens = port { name, args ->
        assertEquals("verifiedHash", name)
        verify(args[0] as String)
    }

    private inline fun <reified T> port(noinline call: (String, Array<out Any?>) -> Any? = { name, _ ->
        error("Unexpected ${T::class.java.simpleName}.$name")
    }): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
        call(method.name.substringBefore('-'), args ?: emptyArray())
    } as T
}
