package com.refinvest.core.tradingreview.port.normalization.compute

import com.refinvest.core.tradingreview.domain.*

sealed interface NormalizationGrantUpdate {
    data object Accepted : NormalizationGrantUpdate
    data object Retry : NormalizationGrantUpdate
    data object Conflict : NormalizationGrantUpdate
    data class Rejected(val code: String) : NormalizationGrantUpdate
}
