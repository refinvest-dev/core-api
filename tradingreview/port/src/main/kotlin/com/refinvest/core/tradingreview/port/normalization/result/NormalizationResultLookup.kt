package com.refinvest.core.tradingreview.port.normalization.result

import com.refinvest.core.tradingreview.domain.*

sealed interface NormalizationResultLookup {
    data class Found(val descriptor: NormalizationResultDescriptor) : NormalizationResultLookup
    data class Retry(val code: String) : NormalizationResultLookup
    data class Rejected(val code: String) : NormalizationResultLookup
}

/** A temporary HTTP entity body. Production downloads use encrypted spool bytes. */
