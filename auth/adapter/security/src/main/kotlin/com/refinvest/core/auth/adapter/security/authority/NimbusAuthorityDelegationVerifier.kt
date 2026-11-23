package com.refinvest.core.auth.adapter.security.authority

import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.refinvest.core.auth.adapter.security.delegation.boundedText
import com.refinvest.core.auth.port.outbound.authority.DelegationVerifier
import com.refinvest.core.auth.port.outbound.authority.VerifiedAuthorityDelegation
import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.core.StreamReadConstraints
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.json.JsonFactory
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.time.Instant
import java.util.Base64

class NimbusAuthorityDelegationVerifier(publicKeys: Map<Pair<String, String>, RSAPublicKey>) : DelegationVerifier {
    private val keys: Map<Pair<String, String>, RSAPublicKey>
    private val json = JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(3).maxStringLength(4096).maxNameLength(64).maxTokenCount(128).build())
        .build()

    init {
        require(publicKeys.size in 1..32) { "Invalid authority trust snapshot" }
        val factory = KeyFactory.getInstance("RSA")
        keys = publicKeys.mapValues { (identity, key) ->
            require(boundedText(identity.first, 512) && boundedText(identity.second, 128) &&
                key.modulus.bitLength() in 2048..4096 && key.modulus.testBit(0) &&
                key.publicExponent == BigInteger.valueOf(65537)) { "Invalid authority trust snapshot" }
            factory.generatePublic(RSAPublicKeySpec(key.modulus, key.publicExponent)) as RSAPublicKey
        }.toMap()
    }

    override fun verify(compact: String, expectedActor: String, now: Instant): VerifiedAuthorityDelegation? = try {
        if (compact.length !in 1..4096 || !callerPattern.matches(expectedActor)) invalid()
        val parts = compact.split('.')
        if (parts.size != 3) invalid()
        val header = objectValue(decode(parts[0]), headerFields)
        val claims = objectValue(decode(parts[1]), claimFields)
        decode(parts[2])
        if (header["alg"] != "RS256" || header["typ"] != "refinvest-user-delegation+jwt") invalid()

        val issuer = claims["iss"] as String
        val kid = header["kid"] as String
        if (!boundedText(issuer, 512) || !boundedText(kid, 128)) invalid()
        val key = keys[issuer to kid] ?: invalid()
        if (!JWSObject.parse(compact).verify(RSASSAVerifier(key))) invalid()

        val subject = claims["sub"] as String
        val member = subject.toLongOrNull()?.takeIf { it > 0 && it.toString() == subject } ?: invalid()
        val actor = (claims["act"] as Map<*, *>)["sub"] as String
        val tokenId = claims["jti"] as String
        val iat = claims["iat"] as Long
        val nbf = claims["nbf"] as Long
        val exp = claims["exp"] as Long
        val second = now.epochSecond
        if (claims["aud"] != AUDIENCE || claims["scope"] != SCOPE || actor != expectedActor ||
            !boundedText(tokenId, 128) || second < 5 || second > Long.MAX_VALUE - 5 ||
            iat <= 0 || exp <= iat || exp - iat > 60 || nbf != iat || iat > second + 5 || exp <= second - 5
        ) invalid()

        VerifiedAuthorityDelegation(member, actor, issuer, kid, AUDIENCE, SCOPE, tokenId, iat, nbf, exp)
    } catch (_: Exception) {
        null
    }

    override fun toString(): String = "NimbusAuthorityDelegationVerifier(redacted)"

    private fun decode(part: String): ByteArray {
        val bytes = Base64.getUrlDecoder().decode(part)
        if (Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) != part) invalid()
        return bytes
    }

    private fun objectValue(bytes: ByteArray, fields: Set<String>): Map<String, Any> {
        val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        return json.createParser(text).use { parser ->
            if (parser.nextToken() != JsonToken.START_OBJECT) invalid()
            val result = readObject(parser, fields)
            if (parser.nextToken() != null) invalid()
            result
        }
    }

    private fun readObject(parser: JsonParser, fields: Set<String>): Map<String, Any> {
        val result = linkedMapOf<String, Any>()
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.PROPERTY_NAME) invalid()
            val name = parser.currentName()
            if (name !in fields || name in result) invalid()
            val token = parser.nextToken()
            result[name] = when {
                name == "act" -> {
                    if (token != JsonToken.START_OBJECT) invalid()
                    readObject(parser, setOf("sub"))
                }
                name in timeFields -> {
                    if (token != JsonToken.VALUE_NUMBER_INT) invalid()
                    parser.getLongValue()
                }
                else -> {
                    if (token != JsonToken.VALUE_STRING) invalid()
                    parser.getString().also { if (!boundedText(it, 4096)) invalid() }
                }
            }
        }
        if (result.keys != fields) invalid()
        return result
    }

    private fun invalid(): Nothing = throw IllegalArgumentException("Invalid delegation")

    companion object {
        const val AUDIENCE = "refinvest:trading-review:owner-query:v1"
        const val SCOPE = "trading-review:owner:read"
        private val callerPattern = Regex("[a-z][a-z0-9-]{0,63}")
        private val headerFields = setOf("alg", "typ", "kid")
        private val timeFields = setOf("iat", "nbf", "exp")
        private val claimFields = setOf("iss", "aud", "sub", "act", "scope", "iat", "nbf", "exp", "jti")
    }
}
