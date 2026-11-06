package com.refinvest.core.tradingreview.domain.valueobject

import java.math.BigDecimal

/** ADR-067 transport policy, pinned when a normalization Run is admitted. */
data class NormalizationResultDeliveryPolicy(
    val policyVersion: String,
    val mode: String,
    val maxAcceptedPayloadBytes: Long,
    val acceptedContentEncodings: List<String>,
) {
    init {
        require(policyVersion == "RESULT_DELIVERY_V1")
        require(mode == "SINGLE_RESOURCE")
        require(maxAcceptedPayloadBytes > 0)
        require(acceptedContentEncodings == listOf("identity"))
    }

    fun toWireValue(): Map<String, Any> = mapOf(
        "policyVersion" to policyVersion,
        "mode" to mode,
        "maxAcceptedPayloadBytes" to maxAcceptedPayloadBytes,
        "acceptedContentEncodings" to acceptedContentEncodings,
    )

    companion object {
        fun fromWireValue(raw: Map<*, *>): NormalizationResultDeliveryPolicy {
            require(raw.keys == setOf("policyVersion", "mode", "maxAcceptedPayloadBytes", "acceptedContentEncodings"))
            val bytes = raw["maxAcceptedPayloadBytes"] as? Number
                ?: throw IllegalArgumentException("Invalid result delivery policy")
            val encodings = raw["acceptedContentEncodings"] as? List<*>
                ?: throw IllegalArgumentException("Invalid result delivery policy")
            return NormalizationResultDeliveryPolicy(
                raw["policyVersion"] as? String ?: throw IllegalArgumentException("Invalid result delivery policy"),
                raw["mode"] as? String ?: throw IllegalArgumentException("Invalid result delivery policy"),
                BigDecimal(bytes.toString()).longValueExact(), encodings.map {
                    it as? String ?: throw IllegalArgumentException("Invalid result delivery policy")
                },
            )
        }
    }
}
