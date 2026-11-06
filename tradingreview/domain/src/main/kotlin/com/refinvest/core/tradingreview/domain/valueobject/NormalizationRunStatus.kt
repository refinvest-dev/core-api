package com.refinvest.core.tradingreview.domain.valueobject

enum class NormalizationRunStatus {
    DISPATCH_PENDING, SUBMITTED, RUNNING, RESULT_READY, PUBLISHING, PUBLISHED,
    RETRY_WAIT, TERMINAL_FAILURE, OBSOLETE
}
