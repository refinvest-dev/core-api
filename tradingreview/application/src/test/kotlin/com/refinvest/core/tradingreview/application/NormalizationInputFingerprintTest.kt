package com.refinvest.core.tradingreview.application

import com.refinvest.core.tradingreview.domain.NormalizationInputCanonicalizer
import com.refinvest.core.tradingreview.domain.NormalizationInputIdentity
import tools.jackson.databind.json.JsonMapper
import java.nio.charset.StandardCharsets
import java.util.HexFormat
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class NormalizationInputFingerprintTest {
    private val mapper = JsonMapper.builder().build()
    private val vector = mapper.readTree(requireNotNull(javaClass.getResourceAsStream(
        "/trading-normalization/input-identity-v1.json")))

    @Suppress("UNCHECKED_CAST")
    private fun root(): MutableMap<String, Any?> =
        mapper.readValue(mapper.writeValueAsString(vector.path("inputIdentity")), Map::class.java)
            as MutableMap<String, Any?>

    private fun identity(root: Map<String, Any?> = root()) = NormalizationInputIdentity.fromWireValue(root)

    @Test
    fun contextVectorMatchesCanonicalUtf8AndSha256() {
        val bytes = NormalizationInputCanonicalizer.canonicalize(identity())
        assertEquals(vector.path("inputIdentity"), mapper.valueToTree(identity().toWireValue()))
        assertContentEquals(vector.path("canonicalJson").asText().toByteArray(StandardCharsets.UTF_8), bytes)
        assertEquals(vector.path("canonicalUtf8Hex").asText(), HexFormat.of().formatHex(bytes))
        assertEquals(vector.path("expectedSha256").asText(), NormalizationInputCanonicalizer.hash(identity()))
        assertFalse(bytes.take(3) == listOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()))
        assertFalse(bytes.last() == '\n'.code.toByte())
        assertEquals("TRADING_INPUT_SET_HASH_V1", identity().hashSpecificationVersion)
    }

    @Test
    fun mapOrderAndLocaleDoNotChangeHashAndJcsUsesUtf16KeyOrder() {
        val baseline = NormalizationInputCanonicalizer.hash(identity())
        val reordered = root()
        @Suppress("UNCHECKED_CAST")
        val artifacts = reordered["artifactsByRole"] as Map<String, Any?>
        reordered["artifactsByRole"] = linkedMapOf(
            "POSITION_HISTORY" to artifacts.getValue("POSITION_HISTORY"),
            "TRADE_HISTORY" to artifacts.getValue("TRADE_HISTORY"))
        val oldLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(baseline, NormalizationInputCanonicalizer.hash(identity(reordered)))
        } finally { Locale.setDefault(oldLocale) }

        val unicode = root()
        @Suppress("UNCHECKED_CAST")
        val version = unicode["targetVersionSet"] as MutableMap<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val reconstruction = version["reconstruction"] as MutableMap<String, Any?>
        val supplementary = "\uD800\uDC00"
        val bmp = "\uE000"
        reconstruction["instrumentTermsVersionByInstrument"] =
            linkedMapOf(bmp to "TERMS_V1", supplementary to "TERMS_V1")
        val canonical = String(NormalizationInputCanonicalizer.canonicalize(identity(unicode)),
            StandardCharsets.UTF_8)
        assertTrue(canonical.indexOf("\"" + supplementary + "\"") <
            canonical.indexOf("\"" + bmp + "\""))
    }

    @Test
    fun logicalFieldChangesChangeHashAndUnsupportedShapeIsRejected() {
        val baseline = NormalizationInputCanonicalizer.hash(identity())
        fun changed(change: (MutableMap<String, Any?>) -> Unit): String {
            val copy = root()
            change(copy)
            return NormalizationInputCanonicalizer.hash(identity(copy))
        }
        assertNotEquals(baseline, changed { it["tradingBookId"] = "other-book" })
        assertNotEquals(baseline, changed { it["sourceImportSessionIds"] = listOf("other-session") })
        assertNotEquals(baseline, changed { it["sourceTimezone"] = "UTC" })
        assertNotEquals(baseline, changed { it["reviewTimezone"] = "Asia/Seoul" })
        assertNotEquals(baseline, changed {
            @Suppress("UNCHECKED_CAST")
            val artifacts = it["artifactsByRole"] as MutableMap<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val trade = artifacts["TRADE_HISTORY"] as MutableMap<String, Any?>
            trade["sha256"] = "c".repeat(64)
        })
        assertNotEquals(baseline, changed {
            @Suppress("UNCHECKED_CAST")
            val version = it["targetVersionSet"] as MutableMap<String, Any?>
            version["versionSetHash"] = "f".repeat(64)
        })
        assertFailsWith<IllegalArgumentException> {
            val invalid = root()
            invalid["hashSpecificationVersion"] = "TRADING_INPUT_SET_HASH_V2"
            identity(invalid)
        }
        assertFailsWith<IllegalArgumentException> {
            val invalid = root()
            @Suppress("UNCHECKED_CAST")
            val product = invalid["declaredProduct"] as MutableMap<String, Any?>
            product["venue"] = "OTHER"
            identity(invalid)
        }
        assertFailsWith<IllegalArgumentException> {
            val invalid = root()
            invalid["declaredPositionMode"] = "HEDGE"
            identity(invalid)
        }
        assertFailsWith<IllegalArgumentException> {
            val invalid = root()
            invalid["sourceImportSessionIds"] = listOf("same", "same")
            identity(invalid)
        }
        assertFailsWith<IllegalArgumentException> {
            val invalid = root()
            @Suppress("UNCHECKED_CAST")
            val artifacts = invalid["artifactsByRole"] as MutableMap<String, Any?>
            artifacts.remove("POSITION_HISTORY")
            identity(invalid)
        }
    }

    @Test
    fun unicodeIsUtf8AndOnlyRequiredCharactersAreEscaped() {
        val changed = root()
        @Suppress("UNCHECKED_CAST")
        val artifacts = changed["artifactsByRole"] as MutableMap<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val trade = artifacts["TRADE_HISTORY"] as MutableMap<String, Any?>
        trade["artifactId"] = "synthetic/\uD83D\uDD12\n"
        val canonical = String(NormalizationInputCanonicalizer.canonicalize(identity(changed)),
            StandardCharsets.UTF_8)
        assertTrue(canonical.contains("synthetic/\uD83D\uDD12\\n"))
        assertFalse(canonical.contains("\\/"))
        assertFailsWith<IllegalArgumentException> {
            trade["artifactId"] = "\uD800"
            NormalizationInputCanonicalizer.canonicalize(identity(changed))
        }
    }
}
