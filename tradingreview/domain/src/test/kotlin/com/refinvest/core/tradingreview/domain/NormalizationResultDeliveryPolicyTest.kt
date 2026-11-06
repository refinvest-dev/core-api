package com.refinvest.core.tradingreview.domain

import com.refinvest.core.tradingreview.domain.valueobject.NormalizationResultDeliveryPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NormalizationResultDeliveryPolicyTest {
    @Test
    fun pinnedPolicyRoundTripsWithoutChangingTheTransportLimit() {
        val policy = NormalizationResultDeliveryPolicy(
            "RESULT_DELIVERY_V1", "SINGLE_RESOURCE", 52_428_800, listOf("identity"))
        assertEquals(policy, NormalizationResultDeliveryPolicy.fromWireValue(policy.toWireValue()))
    }

    @Test
    fun fractionalOrUnsupportedLimitsCannotBePinned() {
        val fields = mapOf("policyVersion" to "RESULT_DELIVERY_V1", "mode" to "SINGLE_RESOURCE",
            "maxAcceptedPayloadBytes" to 1.5, "acceptedContentEncodings" to listOf("identity"))
        assertFailsWith<ArithmeticException> { NormalizationResultDeliveryPolicy.fromWireValue(fields) }
        assertFailsWith<IllegalArgumentException> {
            NormalizationResultDeliveryPolicy.fromWireValue(fields +
                mapOf("maxAcceptedPayloadBytes" to 1L, "acceptedContentEncodings" to listOf("gzip")))
        }
    }
}
