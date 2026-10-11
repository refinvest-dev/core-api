package com.refinvest.core.config

import tools.jackson.databind.ObjectMapper
import com.refinvest.core.tradingreview.port.TradingFailure
import com.refinvest.core.tradingreview.port.TradingVersionPins
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class ConfiguredTradingVersionPins(
    @Value("\${refinvest.trading-review.version-set-json:}") private val configured: String,
    private val mapper: ObjectMapper,
) : TradingVersionPins {
    override fun currentJson(): String {
        if (configured.isBlank()) throw TradingFailure("VERSION_UNAVAILABLE", 422)
        try {
            val node = mapper.readTree(configured)
            val hash = node.path("versionSetHash").asText()
            val normalizer = node.path("normalizer")
            val reconstruction = node.path("reconstruction")
            val normalizerFields = listOf("adapterVersion", "normalizerVersion", "canonicalSchemaVersion",
                "implementationDigest", "versionSetHash")
            val reconstructionFields = listOf("reconstructionPolicyVersion", "reconciliationVersion",
                "reviewUnitSchemaVersion", "evidenceSchemaVersion", "implementationDigest", "versionSetHash")
            if (!hash.matches(SHA256) || normalizerFields.any { normalizer.path(it).asText().isBlank() } ||
                reconstructionFields.any { reconstruction.path(it).asText().isBlank() } ||
                !normalizer.path("versionSetHash").asText().matches(SHA256) ||
                !reconstruction.path("versionSetHash").asText().matches(SHA256) ||
                listOf("TRADE_HISTORY", "POSITION_HISTORY").any {
                    normalizer.path("sourceDialectVersionsByArtifactRole").path(it).asText().isBlank()
                } || !reconstruction.path("instrumentTermsVersionByInstrument").isObject ||
                configured.contains("latest", ignoreCase = true)) throw IllegalArgumentException()
            return mapper.writeValueAsString(node)
        } catch (_: Exception) {
            throw TradingFailure("VERSION_UNAVAILABLE", 422)
        }
    }

    private companion object { val SHA256 = Regex("[0-9a-f]{64}") }
}
