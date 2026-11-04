package com.refinvest.core.tradingreview.adapter.security

import com.refinvest.core.tradingreview.port.common.failure.TradingFailure
import com.refinvest.core.tradingreview.port.common.failure.TradingFailureKind
import com.refinvest.core.tradingreview.port.outbound.artifactgrant.ArtifactGrantTokens
import com.refinvest.core.tradingreview.port.outbound.artifactgrant.IssuedArtifactGrantToken
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/** Owns opaque token/URI format and signing material; it never reads persistence or raw storage. */
@Component
class HmacArtifactGrantTokens(
    @Value("\${refinvest.trading-review.grant-base-url:}") private val baseUrl: String,
    @Value("\${REFINVEST_INTERNAL_API_KEY:}") private val secret: String,
) : ArtifactGrantTokens {
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    override fun requireIssuanceAvailable() {
        if (baseUrl.isBlank() || secret.isBlank())
            throw TradingFailure("TEMPORARY_DEPENDENCY_FAILURE", TradingFailureKind.DEPENDENCY_UNAVAILABLE)
    }

    override fun issue(): IssuedArtifactGrantToken {
        requireIssuanceAvailable()
        val part = encoder.encodeToString(ByteArray(32).also(random::nextBytes))
        val token = part + "." + encoder.encodeToString(mac(part))
        return IssuedArtifactGrantToken(sha(token),
            baseUrl.trimEnd('/') + "/internal/trading-review/artifact-grants/" + token)
    }

    override fun verifiedHash(token: String): String? {
        if (secret.isBlank() || token.length !in 80..120) return null
        val parts = token.split('.')
        if (parts.size != 2 || !MessageDigest.isEqual(
                try { Base64.getUrlDecoder().decode(parts[1]) } catch (_: Exception) { byteArrayOf() },
                mac(parts[0]))) return null
        return sha(token)
    }

    private fun mac(text: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        doFinal(text.toByteArray(Charsets.US_ASCII))
    }

    private fun sha(text: String) = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.US_ASCII)).joinToString("") { "%02x".format(it) }
}
