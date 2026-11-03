package com.refinvest.core.tradingreview.port.outbound.compute.result

import com.refinvest.core.tradingreview.port.outbound.persistence.normalization.NormalizationPayloadAcknowledgement

import com.refinvest.core.tradingreview.domain.valueobject.NormalizationResultDeliveryPolicy
import com.refinvest.core.tradingreview.domain.*

interface TradingNormalizationResultClient {
    fun descriptor(jobId: String): NormalizationResultLookup
    fun download(descriptor: NormalizationResultDescriptor,
                 policy: NormalizationResultDeliveryPolicy): NormalizationPayloadLookup
    fun acknowledge(acknowledgement: NormalizationPayloadAcknowledgement): NormalizationAckOutcome
}
