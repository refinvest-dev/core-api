package com.refinvest.core.tradingreview.application

import com.refinvest.core.tradingreview.port.common.failure.TradingFailure
import com.refinvest.core.tradingreview.port.common.failure.TradingFailureKind
import com.refinvest.core.tradingreview.port.inbound.artifactgrant.redeem.RedeemTradingArtifactGrantCommand
import com.refinvest.core.tradingreview.port.outbound.artifactgrant.ArtifactReadGrantRedeemer
import com.refinvest.core.tradingreview.port.outbound.artifactgrant.ArtifactRedemption

import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.springframework.transaction.support.TransactionSynchronizationManager

class RedeemTradingArtifactGrantServiceTest {
    @Test
    fun `redemption preserves opaque credentials and transfers stream ownership without reading`() {
        val token = "synthetic.opaque-token"
        val command = RedeemTradingArtifactGrantCommand(token)
        val bytes = "synthetic raw".toByteArray()
        val delegate = bytes.inputStream()
        var reads = 0
        var closed = false
        val stream = object : InputStream() {
            override fun read(): Int { reads++; return delegate.read() }
            override fun close() { closed = true; delegate.close() }
        }
        val capability = object : ArtifactReadGrantRedeemer {
            override fun redeem(token: String): ArtifactRedemption {
                assertEquals(command.token, token)
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
                return ArtifactRedemption(stream, bytes.size.toLong(), "text/csv")
            }
        }

        val result = RedeemTradingArtifactGrantService(capability).execute(command)

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
    fun `rejected redemption keeps the original safe failure and does not retry`() {
        val failure = TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)
        var calls = 0
        val capability = object : ArtifactReadGrantRedeemer {
            override fun redeem(token: String): ArtifactRedemption {
                calls++
                throw failure
            }
        }
        val service = RedeemTradingArtifactGrantService(capability)

        assertSame(failure, assertFailsWith<TradingFailure> {
            service.execute(RedeemTradingArtifactGrantCommand("synthetic.rejected-token"))
        })
        assertEquals(1, calls)
    }
}
