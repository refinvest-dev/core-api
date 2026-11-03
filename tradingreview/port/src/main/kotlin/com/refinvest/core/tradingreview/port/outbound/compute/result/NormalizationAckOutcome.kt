package com.refinvest.core.tradingreview.port.outbound.compute.result

import com.refinvest.core.tradingreview.domain.*

sealed interface NormalizationAckOutcome {
    data object Accepted : NormalizationAckOutcome
    data object Retry : NormalizationAckOutcome
    data class Rejected(val code: String) : NormalizationAckOutcome
}
