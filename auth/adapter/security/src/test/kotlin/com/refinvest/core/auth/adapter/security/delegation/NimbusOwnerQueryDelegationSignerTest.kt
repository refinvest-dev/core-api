package com.refinvest.core.auth.adapter.security.delegation

import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.refinvest.core.auth.port.outbound.delegation.DelegationSigningException
import com.refinvest.core.auth.port.outbound.delegation.OwnerQueryDelegationPayload
import com.refinvest.core.shared.kernel.member.MemberId
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPrivateKeySpec
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NimbusOwnerQueryDelegationSignerTest {
    @Test
    fun `emits exact RS256 profile and preserves a member ID above JavaScript precision`() {
        val signer = signer()

        val compact = signer.sign(payload())
        val signed = JWSObject.parse(compact)
        val claims = signed.payload.toJSONObject()

        assertTrue(signed.verify(RSASSAVerifier(first.public as RSAPublicKey)))
        assertEquals(mapOf("alg" to "RS256", "typ" to "refinvest-user-delegation+jwt", "kid" to "k1"), signed.header.toJSONObject())
        assertEquals(setOf("iss", "aud", "sub", "act", "scope", "iat", "nbf", "exp", "jti"), claims.keys)
        assertEquals(issuer, claims["iss"])
        assertEquals("refinvest:trading-review:owner-query:v1", claims["aud"])
        assertEquals("9007199254740993", claims["sub"])
        assertEquals(mapOf("sub" to "core-api"), claims["act"])
        assertEquals("trading-review:owner:read", claims["scope"])
        assertEquals(now.epochSecond, (claims["iat"] as Number).toLong())
        assertEquals(claims["iat"], claims["nbf"])
        assertEquals(now.epochSecond + 60, (claims["exp"] as Number).toLong())
        assertEquals("test-only-jti", claims["jti"])
        assertTrue(compact.toByteArray(Charsets.US_ASCII).size <= 4096)
    }

    @Test
    fun `a snapshot switch uses the new kid while the previous token remains independently verifiable`() {
        val selected = AtomicReference(key("k1", first))
        var snapshots = 0
        val signer = NimbusOwnerQueryDelegationSigner(issuer) {
            snapshots++
            selected.get()
        }

        val old = signer.sign(payload())
        selected.set(key("k2", second))
        val next = signer.sign(payload())

        assertEquals(2, snapshots)
        assertEquals("k1", JWSObject.parse(old).header.keyID)
        assertEquals("k2", JWSObject.parse(next).header.keyID)
        assertTrue(JWSObject.parse(old).verify(RSASSAVerifier(first.public as RSAPublicKey)))
        assertTrue(JWSObject.parse(next).verify(RSASSAVerifier(second.public as RSAPublicKey)))
        assertFalse(JWSObject.parse(next).verify(RSASSAVerifier(first.public as RSAPublicKey)))
    }

    @Test
    fun `invalid input never consults the key provider`() {
        var calls = 0
        val signer = NimbusOwnerQueryDelegationSigner(issuer) {
            calls++
            error("unused")
        }
        val bad = listOf(
            OwnerQueryDelegationPayload(MemberId(0), "jti", now),
            OwnerQueryDelegationPayload(MemberId(-1), "jti", now),
            OwnerQueryDelegationPayload(MemberId(1), "jti", Instant.EPOCH),
            OwnerQueryDelegationPayload(MemberId(1), "jti", now.plusNanos(1)),
            OwnerQueryDelegationPayload(MemberId(1), "", now),
            OwnerQueryDelegationPayload(MemberId(1), "bad\nvalue", now),
            OwnerQueryDelegationPayload(MemberId(1), "\uD800", now),
            OwnerQueryDelegationPayload(MemberId(1), "x".repeat(129), now),
        )

        bad.forEach {
            val failure = assertFailsWith<DelegationSigningException> { signer.sign(it) }
            assertEquals(DelegationSigningException.Reason.INVALID_INPUT, failure.reason)
            assertNull(failure.cause)
        }
        assertEquals(0, calls)
    }

    @Test
    fun `issuer and kid bounds reject malformed or oversized trust configuration`() {
        listOf("", "bad\rvalue", "\uD800", "x".repeat(513)).forEach { bad ->
            assertFailsWith<DelegationSigningException> { NimbusOwnerQueryDelegationSigner(bad) { key("k1", first) } }
        }
        listOf("", "bad\nvalue", "\uD800", "x".repeat(129)).forEach { bad ->
            assertFailsWith<DelegationSigningException> { key(bad, first) }
        }
    }

    @Test
    fun `key preparation rejects weak mismatched or invalid private material`() {
        val weak = pair(1024)
        assertFailsWith<DelegationSigningException> { key("weak", weak) }
        assertFailsWith<DelegationSigningException> {
            OwnerQuerySigningKey.prepare("mismatch", first.public as RSAPublicKey, second.private as RSAPrivateKey)
        }
        val private = first.private as RSAPrivateKey
        val corrupt = KeyFactory.getInstance("RSA").generatePrivate(
            RSAPrivateKeySpec(private.modulus, private.privateExponent.add(java.math.BigInteger.TWO)),
        ) as RSAPrivateKey

        val failure = assertFailsWith<DelegationSigningException> {
            OwnerQuerySigningKey.prepare("bad-private", first.public as RSAPublicKey, corrupt)
        }
        assertEquals(DelegationSigningException.Reason.INVALID_KEY, failure.reason)
        assertNull(failure.cause)
    }

    @Test
    fun `provider failure never exposes a raw cause token or key`() {
        val raw = "test-only-sensitive-provider-message"
        val signer = NimbusOwnerQueryDelegationSigner(issuer) { throw IllegalStateException(raw) }

        val failure = assertFailsWith<DelegationSigningException> { signer.sign(payload()) }

        assertEquals(DelegationSigningException.Reason.SIGNING_UNAVAILABLE, failure.reason)
        assertEquals("Owner query delegation unavailable", failure.message)
        assertNull(failure.cause)
        assertFalse(failure.toString().contains(raw))
        assertEquals("OwnerQuerySigningKey(redacted)", key("k1", first).toString())
    }

    private fun signer() = NimbusOwnerQueryDelegationSigner(issuer) { prepared }

    private fun payload() = OwnerQueryDelegationPayload(MemberId(9007199254740993), "test-only-jti", now)

    companion object {
        private const val issuer = "https://issuer.invalid/delegation"
        private val now = Instant.ofEpochSecond(1794096000)
        private val first by lazy { pair(2048) }
        private val second by lazy { pair(2048) }
        private val prepared by lazy { key("k1", first) }

        private fun pair(bits: Int): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(bits) }.generateKeyPair()

        private fun key(kid: String, pair: KeyPair) = OwnerQuerySigningKey.prepare(
            kid,
            pair.public as RSAPublicKey,
            pair.private as RSAPrivateKey,
        )
    }
}
