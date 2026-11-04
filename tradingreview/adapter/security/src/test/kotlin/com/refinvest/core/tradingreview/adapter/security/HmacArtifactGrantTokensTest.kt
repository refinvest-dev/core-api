package com.refinvest.core.tradingreview.adapter.security

import com.refinvest.core.tradingreview.port.common.failure.TradingFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HmacArtifactGrantTokensTest {
    private val secret = "synthetic-internal-key"
    private val knownToken = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA.d_VQUDLu8DkLMb5Aan3JiVjOLyveA8xniMf5Dh8ph_g"

    @Test
    fun `existing HMAC token vector and issued URI format remain compatible`() {
        val tokens = HmacArtifactGrantTokens("https://core.test/", secret)
        // Independently computed HMAC-SHA256 and ASCII token SHA-256, using a 32-byte zero nonce.
        assertEquals("88513591da9641f72ba77cffb0231930599a2bbd5f3c30e724057c922c88d0a1", tokens.verifiedHash(knownToken))
        val issued = tokens.issue()
        val value = issued.accessUri.removePrefix("https://core.test/internal/trading-review/artifact-grants/")
        assertEquals(87, value.length)
        assertEquals(issued.hash, tokens.verifiedHash(value))
        assertFalse(issued.toString().contains(value))
        assertFalse(issued.toString().contains(issued.accessUri))
    }

    @Test
    fun `tampered malformed and other-key credentials never produce a verified fingerprint`() {
        val tokens = HmacArtifactGrantTokens("https://core.test", secret)
        for (token in listOf("", "invalid", knownToken.replaceFirst('A', 'B'), knownToken + ".extra",
                knownToken.substringBefore('.') + "." + "!".repeat(43))) {
            assertNull(tokens.verifiedHash(token))
        }
        assertNull(HmacArtifactGrantTokens("https://core.test", "another-synthetic-key").verifiedHash(knownToken))
        assertNull(HmacArtifactGrantTokens("https://core.test", "").verifiedHash(knownToken))
    }

    @Test
    fun `issuance requires configuration but verification does not require an issue base URL`() {
        assertNotNull(HmacArtifactGrantTokens("", secret).verifiedHash(knownToken))
        for (tokens in listOf(HmacArtifactGrantTokens("", secret), HmacArtifactGrantTokens("https://core.test", ""))) {
            assertEquals("TEMPORARY_DEPENDENCY_FAILURE", assertFailsWith<TradingFailure> { tokens.issue() }.code)
        }
    }
}
