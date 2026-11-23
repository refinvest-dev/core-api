package com.refinvest.core.auth.adapter.security.authority

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.crypto.MACSigner
import com.refinvest.core.auth.adapter.security.delegation.NimbusOwnerQueryDelegationSigner
import com.refinvest.core.auth.adapter.security.delegation.OwnerQuerySigningKey
import com.refinvest.core.auth.port.outbound.delegation.OwnerQueryDelegationPayload
import com.refinvest.core.shared.kernel.member.MemberId
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import kotlin.test.*

class AuthorityVerificationTest {
    @Test
    fun `verifies actual issuer with exact identity and integer precision`() {
        val verified = assertNotNull(verifier().verify(token(), "core-api", now))

        assertEquals(Long.MAX_VALUE, verified.subject)
        assertEquals("core-api", verified.actor)
        assertEquals(issuer, verified.issuer)
        assertEquals("k1", verified.keyId)
        assertEquals("test-only-authority-jti", verified.tokenId)
        assertEquals(now.epochSecond + 60, verified.expiresAt)
        assertEquals("VerifiedAuthorityDelegation(redacted)", verified.toString())
    }

    @Test
    fun `enforces expiry leeway future issuance actor and scalar audience`() {
        val token = token()
        val verifier = verifier()
        assertNotNull(verifier.verify(token, "core-api", now.plusSeconds(64)))
        assertNull(verifier.verify(token, "core-api", now.plusSeconds(65)))
        assertNull(verifier.verify(token, "core-api", now.minusSeconds(6)))
        assertNull(verifier.verify(token, "gateway", now))
        assertNull(verifier.verify(replace("aud", listOf(NimbusAuthorityDelegationVerifier.AUDIENCE)), "core-api", now))
    }

    @Test
    fun `rejects signed malformed duplicate unknown or lossy claims`() {
        val parsed = JWSObject.parse(token())
        val raw = parsed.payload.toString().dropLast(1) + ",\"sub\":\"1\"}"
        val cases = listOf(
            resign(parsed.header, Payload(raw)),
            replace("role", "ADMIN"), replace("sub", 1L), replace("sub", "01"),
            replace("iat", 1.5), replace("iat", "1794096000"), replace("nbf", now.epochSecond + 1),
            replace("exp", now.epochSecond + 61),
            resign(parsed.header, Payload(parsed.payload.toString().replace("\"test-only-authority-jti\"", "\"\\uD800\""))),
            replace("scope", "trading-review:write"), replace("iss", "https://wrong.invalid"),
            replace("act", mapOf("sub" to "core-api", "role" to "ADMIN")),
        )
        cases.forEachIndexed { index, token -> assertNull(verifier().verify(token, "core-api", now), "negative case $index") }
    }

    @Test
    fun `rejects modified signature remote key headers wrong type kid and oversized input`() {
        val parsed = JWSObject.parse(token())
        val segments = token().split('.')
        val browser = JWSObject(JWSHeader(JWSAlgorithm.HS256), parsed.payload).apply { sign(MACSigner(ByteArray(32) { 7 })) }
        val cases = listOf(
            segments[0] + "." + segments[1] + "." + "A".repeat(342),
            resign(JWSHeader.Builder(parsed.header).type(JOSEObjectType("JWT")).build(), parsed.payload),
            resign(JWSHeader.Builder(parsed.header).keyID("unknown").build(), parsed.payload),
            resign(JWSHeader.Builder(parsed.header).jwkURL(java.net.URI("https://unreachable.invalid/jwks")).build(), parsed.payload),
            "a".repeat(4097), token() + "=", browser.serialize(),
        )
        cases.forEach { assertNull(verifier().verify(it, "core-api", now)) }
    }

    @Test
    fun `trust snapshot and credential snapshot do not follow caller mutation`() {
        val keys = mutableMapOf((issuer to "k1") to (pair.public as RSAPublicKey))
        val verifier = NimbusAuthorityDelegationVerifier(keys)
        keys.clear()
        assertNotNull(verifier.verify(token(), "core-api", now))

        val credentials = mutableListOf("A".repeat(43), "B".repeat(43))
        val caller = OpaqueAuthorityCredentialVerifier(credentials)
        credentials.clear()
        assertTrue(caller.accepts("A".repeat(43)))
        assertTrue(caller.accepts("B".repeat(43)))
        assertFalse(caller.accepts("C".repeat(43)))
        assertFalse(caller.accepts(token()))
        assertFalse(caller.accepts("short"))
        assertEquals("OpaqueAuthorityCredentialVerifier(redacted)", caller.toString())
        assertFailsWith<IllegalArgumentException> { OpaqueAuthorityCredentialVerifier(listOf("A".repeat(43), "A".repeat(43))) }
    }

    private fun verifier() = NimbusAuthorityDelegationVerifier(mapOf((issuer to "k1") to (pair.public as RSAPublicKey)))

    private fun token() = signer.sign(OwnerQueryDelegationPayload(MemberId(Long.MAX_VALUE), "test-only-authority-jti", now))

    private fun replace(name: String, value: Any): String {
        val parsed = JWSObject.parse(token())
        val fields = LinkedHashMap(parsed.payload.toJSONObject()).apply { put(name, value) }
        return resign(parsed.header, Payload(fields))
    }

    private fun resign(header: JWSHeader, payload: Payload): String =
        JWSObject(header, payload).apply { sign(RSASSASigner(pair.private as RSAPrivateKey)) }.serialize()

    companion object {
        private const val issuer = "https://issuer.invalid/delegation"
        private val now = Instant.ofEpochSecond(1794096000)
        private val pair by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }
        private val signingKey by lazy { OwnerQuerySigningKey.prepare("k1", pair.public as RSAPublicKey, pair.private as RSAPrivateKey) }
        private val signer by lazy { NimbusOwnerQueryDelegationSigner(issuer) { signingKey } }
    }
}
