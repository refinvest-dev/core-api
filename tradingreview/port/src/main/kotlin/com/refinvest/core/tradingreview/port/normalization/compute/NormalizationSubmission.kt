package com.refinvest.core.tradingreview.port.normalization.compute

import com.refinvest.core.tradingreview.domain.*
import java.time.Duration

sealed interface NormalizationSubmission {
    data class Accepted(val jobId: String) : NormalizationSubmission
    data class Retry(val code: String, val retryAfter: Duration? = null, val outcomeUnknown: Boolean = false) : NormalizationSubmission
    data class Rejected(val code: String) : NormalizationSubmission
}
