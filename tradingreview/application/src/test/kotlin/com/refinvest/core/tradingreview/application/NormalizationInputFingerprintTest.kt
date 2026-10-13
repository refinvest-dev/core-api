package com.refinvest.core.tradingreview.application

import com.refinvest.core.tradingreview.domain.*
import tools.jackson.databind.ObjectMapper
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class NormalizationInputFingerprintTest {
    private val mapper = ObjectMapper()
    private val trade = NormalizationArtifactInput(TradingSourceArtifactId(10), TradingArtifactRole.TRADE_HISTORY,
        "a".repeat(64), 100, "text/csv", "BINANCE_USDS_TRADE_HISTORY_V1")
    private val position = NormalizationArtifactInput(TradingSourceArtifactId(11), TradingArtifactRole.POSITION_HISTORY,
        "b".repeat(64), 200, "text/csv", "BINANCE_USDS_POSITION_HISTORY_V1")
    private fun input(artifacts: List<NormalizationArtifactInput> = listOf(trade, position),
                      versions: String = "{\"versionSetHash\":\"one\",\"normalizer\":{\"b\":2,\"a\":1}}",
                      generation: Long = 0) = NormalizationInput(TradingBookId(1), TradingImportSessionId(2), 3,
        artifacts, "BINANCE", "LINEAR_PERPETUAL", "ONE_WAY", "USDT", "Asia/Seoul", "UTC",
        versions, "[]", "TRADING_DATA_POLICY_V1", "retry-v1", "{}", generation)

    @Test
    fun canonicalIdentityIgnoresMapOrderAndLocale() {
        val original = Locale.getDefault()
        try {
            val first = NormalizationInputFingerprint.hash(input(), mapper)
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val second = NormalizationInputFingerprint.hash(input(listOf(position, trade),
                "{\"normalizer\":{\"a\":1,\"b\":2},\"versionSetHash\":\"one\"}"), mapper)
            assertEquals(first, second)
        } finally { Locale.setDefault(original) }
    }

    @Test
    fun artifactHashAndVersionPinChangeIdentityWhileGenerationDoesNot() {
        val base = NormalizationInputFingerprint.hash(input(), mapper)
        assertNotEquals(base, NormalizationInputFingerprint.hash(input(listOf(trade.copy(sha256 = "c".repeat(64)), position)), mapper))
        assertNotEquals(base, NormalizationInputFingerprint.hash(input(versions = "{\"versionSetHash\":\"two\"}"), mapper))
        assertEquals(base, NormalizationInputFingerprint.hash(input(generation = 1), mapper))
    }

    @Test
    fun objectKeysUseUnicodeCodePointOrder() {
        val supplementary = "\uD800\uDC00"
        val bmp = "\uE000"
        assertEquals("{\"$bmp\":2,\"$supplementary\":1}",
            NormalizationInputFingerprint.canonical(mapOf(supplementary to 1, bmp to 2), mapper))
    }
}
