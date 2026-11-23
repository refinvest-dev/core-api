package com.refinvest

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.util.JSONObjectUtils
import com.refinvest.core.auth.adapter.security.delegation.NimbusOwnerQueryDelegationSigner
import com.refinvest.core.auth.adapter.security.delegation.OwnerQuerySigningKey
import com.refinvest.core.auth.application.delegation.IssueOwnerQueryDelegationService
import com.refinvest.core.auth.port.outbound.member.CurrentMemberIdProvider
import com.refinvest.core.shared.kernel.member.MemberId
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OwnerQueryDelegationInteropTest {
    @Test
    fun `exports actual Kotlin issuer tokens and signed negative vectors without private credentials`() {
        val first = pair(2048)
        val second = pair(4096)
        val now = 1794096000L
        val issuer = "https://issuer.invalid/delegation"
        val subject = 9007199254740993L
        val original = issue(first, "k1", issuer, subject, now)
        val signed = JWSObject.parse(original)
        val claims = signed.payload.toJSONObject()
        assertEquals("refinvest:trading-review:owner-query:v1", claims["aud"])
        assertEquals(subject.toString(), claims["sub"])

        val cases = mutableListOf<Map<String, Any>>()
        fun add(name: String, token: String, at: Long = now, allowed: Boolean = false, member: Long = subject) {
            cases.add(linkedMapOf("name" to name, "token" to token, "now" to at, "allowed" to allowed, "memberId" to member.toString()))
        }
        fun replaceClaim(name: String, field: String, value: Any) {
            add(name, resign(first, signed.header, Payload(LinkedHashMap(claims).apply { put(field, value) })))
        }

        add("kotlin-core-large-member", original, allowed = true)
        add("kotlin-core-max-int64", issue(first, "k1", issuer, Long.MAX_VALUE, now), allowed = true, member = Long.MAX_VALUE)
        add("kotlin-core-4096-key", issue(second, "k2", issuer, subject, now), allowed = true)
        add("within-expiry-leeway", original, now + 64, allowed = true)
        add("expiry-leeway-boundary", original, now + 65)
        add("issued-too-far-in-future", original, now - 6)
        add("wrong-issuer", issue(first, "k1", "https://wrong.invalid/delegation", subject, now))
        add("unknown-kid", issue(first, "unknown", issuer, subject, now))
        replaceClaim("wrong-audience", "aud", "refinvest:another-service")
        replaceClaim("wrong-actor", "act", mapOf("sub" to "gateway"))
        replaceClaim("array-audience", "aud", listOf("refinvest:trading-review:owner-query:v1"))
        replaceClaim("extra-role", "role", "ADMIN")
        add("browser-token-type", resign(first, JWSHeader.Builder(signed.header).type(JOSEObjectType("JWT")).build(), signed.payload))
        add("remote-key-header", resign(first, JWSHeader.Builder(signed.header).jwkURL(java.net.URI("https://key.invalid/jwks")).build(), signed.payload))

        val segments = original.split('.')
        val changedPayload = JSONObjectUtils.toJSONString(LinkedHashMap(claims).apply { put("sub", "1") })
        add("unsigned-payload-change", segments[0] + "." + base64(changedPayload.toByteArray()) + "." + segments[2])
        val corrupt = Base64.getUrlDecoder().decode(segments[2]).apply { this[0] = (this[0].toInt() xor 1).toByte() }
        add("bad-signature", segments[0] + "." + segments[1] + "." + base64(corrupt))

        val duplicateClaims = signed.payload.toString().dropLast(1) + ",\"sub\":\"1\"}"
        add("duplicate-subject", resign(first, signed.header, Payload(duplicateClaims)))
        val browser = JWSObject(JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType("JWT")).build(), signed.payload)
        browser.sign(MACSigner(ByteArray(32) { 7 }))
        add("browser-hs256", browser.serialize())

        val exported = linkedMapOf<String, Any>(
            "schemaVersion" to 1,
            "testOnly" to true,
            "producer" to "Core IssueOwnerQueryDelegationService + NimbusOwnerQueryDelegationSigner",
            "issuer" to issuer,
            "issuedAt" to now,
            "keys" to listOf(publicKey("k1", first), publicKey("k2", second)),
            "cases" to cases,
        )
        val json = JSONObjectUtils.toJSONString(exported)
        assertEquals(18, cases.size)
        assertTrue(cases.count { it["allowed"] == true } == 4)
        assertFalse(json.contains("PRIVATE KEY"))
        assertTrue((exported["keys"] as List<*>).all { (it as Map<*, *>).keys == setOf("kid", "n", "e") })

        // Only test public material is exported. Private keys exist solely in this test's memory.
        val output = Path.of("build/delegation-interop/vectors.json")
        Files.createDirectories(output.parent)
        Files.writeString(output, json + "\n")
    }

    private fun issue(pair: KeyPair, kid: String, issuer: String, member: Long, now: Long): String {
        val key = OwnerQuerySigningKey.prepare(kid, pair.public as RSAPublicKey, pair.private as RSAPrivateKey)
        val signer = NimbusOwnerQueryDelegationSigner(issuer) { key }
        val service = IssueOwnerQueryDelegationService(
            CurrentMemberIdProvider { MemberId(member) },
            signer,
            Clock.fixed(Instant.ofEpochSecond(now), ZoneOffset.UTC),
            { "test-only-kotlin-jti" },
        )

        return service.execute().compactToken
    }

    private fun resign(pair: KeyPair, header: JWSHeader, payload: Payload): String =
        JWSObject(header, payload).apply { sign(RSASSASigner(pair.private as RSAPrivateKey)) }.serialize()

    private fun publicKey(kid: String, pair: KeyPair): Map<String, Any> {
        val key = pair.public as RSAPublicKey
        val signed = key.modulus.toByteArray()
        val unsigned = if (signed[0] == 0.toByte()) signed.copyOfRange(1, signed.size) else signed

        return mapOf("kid" to kid, "n" to base64(unsigned), "e" to key.publicExponent.toInt())
    }

    private fun base64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun pair(bits: Int): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(bits) }.generateKeyPair()
}
