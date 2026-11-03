package com.refinvest.core.tradingreview.port.outbound.compute.normalization

import com.refinvest.core.tradingreview.domain.*

sealed interface NormalizationStatusLookup {
    data class Found(val status: NormalizationJobStatus) : NormalizationStatusLookup
    data class Retry(val code: String) : NormalizationStatusLookup
    data class Rejected(val code: String) : NormalizationStatusLookup
}
