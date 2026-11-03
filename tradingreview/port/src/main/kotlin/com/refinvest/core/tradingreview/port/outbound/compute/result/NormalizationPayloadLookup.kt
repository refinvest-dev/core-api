package com.refinvest.core.tradingreview.port.outbound.compute.result

import com.refinvest.core.tradingreview.domain.*

sealed interface NormalizationPayloadLookup {
    data class Found(val payload: NormalizationPayloadFile) : NormalizationPayloadLookup
    data class Retry(val code: String) : NormalizationPayloadLookup
    data class Rejected(val code: String) : NormalizationPayloadLookup
}
