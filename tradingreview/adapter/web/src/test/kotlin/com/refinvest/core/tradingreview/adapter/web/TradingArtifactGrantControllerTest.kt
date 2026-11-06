package com.refinvest.core.tradingreview.adapter.web

import com.refinvest.core.tradingreview.adapter.web.artifactgrant.TradingArtifactGrantController
import com.refinvest.core.tradingreview.adapter.web.error.TradingReviewExceptionHandler

import com.refinvest.core.tradingreview.port.common.failure.TradingFailure
import com.refinvest.core.tradingreview.port.common.failure.TradingFailureKind
import com.refinvest.core.tradingreview.port.inbound.artifactgrant.redeem.RedeemTradingArtifactGrantResult
import com.refinvest.core.tradingreview.port.inbound.artifactgrant.redeem.RedeemTradingArtifactGrantUseCase

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class TradingArtifactGrantControllerTest {
    @Test
    fun `inbound result is streamed with original metadata and closed after the response`() {
        val token = "synthetic.opaque-token"
        val bytes = "synthetic raw".toByteArray()
        var closed = false
        val stream = object : java.io.ByteArrayInputStream(bytes) {
            override fun close() { closed = true; super.close() }
        }
        val useCase = RedeemTradingArtifactGrantUseCase { command ->
            assertEquals(token, command.token)
            assertFalse(closed)
            RedeemTradingArtifactGrantResult(stream, bytes.size.toLong(), "text/csv")
        }
        val mvc = MockMvcBuilders.standaloneSetup(TradingArtifactGrantController(useCase)).build()

        val response = mvc.perform(get("/internal/trading-review/artifact-grants/{token}", token))
            .andExpect(status().isOk).andReturn().response

        assertTrue(response.contentAsByteArray.contentEquals(bytes))
        assertEquals("text/csv", response.contentType)
        assertEquals(bytes.size.toLong(), response.getHeader("Content-Length")?.toLong())
        assertTrue(closed)
    }

    @Test
    fun `safe redemption failure is mapped without exposing the opaque token`() {
        val token = "synthetic.rejected-token"
        val useCase = RedeemTradingArtifactGrantUseCase {
            throw TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)
        }
        val mvc = MockMvcBuilders.standaloneSetup(TradingArtifactGrantController(useCase))
            .setControllerAdvice(TradingReviewExceptionHandler()).build()

        val response = mvc.perform(get("/internal/trading-review/artifact-grants/{token}", token))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
            .andReturn().response

        assertFalse(response.contentAsString.contains(token))
    }
}
