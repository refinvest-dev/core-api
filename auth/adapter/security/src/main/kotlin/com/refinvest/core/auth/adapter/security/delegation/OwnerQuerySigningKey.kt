package com.refinvest.core.auth.adapter.security.delegation

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.refinvest.core.auth.port.outbound.delegation.DelegationSigningException
import com.refinvest.core.auth.port.outbound.delegation.DelegationSigningException.Reason.INVALID_KEY
import java.math.BigInteger
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPrivateKeySpec
import java.security.spec.RSAPublicKeySpec

class OwnerQuerySigningKey private constructor(
    internal val keyId: String,
    private val privateKey: RSAPrivateKey,
) {
    internal fun sign(value: JWSObject) {
        value.sign(RSASSASigner(privateKey))
    }

    override fun toString(): String = "OwnerQuerySigningKey(redacted)"

    companion object {
        fun prepare(keyId: String, publicKey: RSAPublicKey, privateKey: RSAPrivateKey): OwnerQuerySigningKey {
            try {
                if (!boundedText(keyId, 128) ||
                    publicKey.modulus.bitLength() !in 2048..4096 ||
                    !publicKey.modulus.testBit(0) ||
                    publicKey.publicExponent != BigInteger.valueOf(65537) ||
                    privateKey.modulus != publicKey.modulus ||
                    privateKey.privateExponent.signum() <= 0
                ) {
                    throw DelegationSigningException(INVALID_KEY)
                }

                val factory = KeyFactory.getInstance("RSA")
                val publicCopy = factory.generatePublic(
                    RSAPublicKeySpec(publicKey.modulus, publicKey.publicExponent),
                ) as RSAPublicKey
                val privateCopy = factory.generatePrivate(
                    RSAPrivateKeySpec(privateKey.modulus, privateKey.privateExponent),
                ) as RSAPrivateKey

                val key = OwnerQuerySigningKey(keyId, privateCopy)
                val proof = JWSObject(JWSHeader(JWSAlgorithm.RS256), Payload("owner-query-key-preparation"))
                key.sign(proof)
                if (!proof.verify(RSASSAVerifier(publicCopy))) throw DelegationSigningException(INVALID_KEY)

                return key
            } catch (_: Exception) {
                throw DelegationSigningException(INVALID_KEY)
            }
        }
    }
}

internal fun boundedText(value: String, maxBytes: Int): Boolean =
    value.length in 1..maxBytes &&
        value.isNotBlank() &&
        value.none { Character.isISOControl(it) } &&
        Charsets.UTF_8.newEncoder().canEncode(value) &&
        value.toByteArray(Charsets.UTF_8).size <= maxBytes
