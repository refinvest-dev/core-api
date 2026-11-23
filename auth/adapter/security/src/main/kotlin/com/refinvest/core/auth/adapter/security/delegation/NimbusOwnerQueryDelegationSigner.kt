package com.refinvest.core.auth.adapter.security.delegation

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSObject
import com.nimbusds.jose.Payload
import com.refinvest.core.auth.port.outbound.delegation.DelegationSigningException
import com.refinvest.core.auth.port.outbound.delegation.DelegationSigningException.Reason.INVALID_INPUT
import com.refinvest.core.auth.port.outbound.delegation.DelegationSigningException.Reason.SIGNING_UNAVAILABLE
import com.refinvest.core.auth.port.outbound.delegation.OwnerQueryDelegationPayload
import com.refinvest.core.auth.port.outbound.delegation.OwnerQueryDelegationSigner

class NimbusOwnerQueryDelegationSigner(
    private val issuer: String,
    private val signingKey: () -> OwnerQuerySigningKey,
) : OwnerQueryDelegationSigner {
    init {
        if (!boundedText(issuer, 512)) throw DelegationSigningException(INVALID_INPUT)
    }

    override fun sign(payload: OwnerQueryDelegationPayload): String {
        val issuedAt = payload.issuedAt.epochSecond
        if (payload.memberId.value <= 0 || payload.issuedAt.nano != 0 || issuedAt <= 0 ||
            issuedAt > Long.MAX_VALUE - OwnerQueryDelegationPayload.TTL_SECONDS || !boundedText(payload.tokenId, 128)
        ) {
            throw DelegationSigningException(INVALID_INPUT)
        }

        try {
            // Capture exactly one prepared snapshot; this trusted getter performs no remote I/O.
            val key = signingKey()
            val header = JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType("refinvest-user-delegation+jwt"))
                .keyID(key.keyId)
                .build()
            val claims = linkedMapOf<String, Any>(
                "iss" to issuer,
                "aud" to "refinvest:trading-review:owner-query:v1",
                "sub" to payload.memberId.value.toString(),
                "act" to mapOf("sub" to "core-api"),
                "scope" to "trading-review:owner:read",
                "iat" to issuedAt,
                "nbf" to issuedAt,
                "exp" to issuedAt + OwnerQueryDelegationPayload.TTL_SECONDS,
                "jti" to payload.tokenId,
            )

            val signed = JWSObject(header, Payload(claims))
            key.sign(signed)
            val compact = signed.serialize()
            if (compact.toByteArray(Charsets.US_ASCII).size > 4096) throw DelegationSigningException(SIGNING_UNAVAILABLE)

            return compact
        } catch (_: Exception) {
            // Never wrap provider/library messages: they may carry key material or token input.
            throw DelegationSigningException(SIGNING_UNAVAILABLE)
        }
    }
}
