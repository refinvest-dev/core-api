package com.refinvest.core.tradingreview.domain.valueobject

enum class DeletionRequestState {
    REQUESTED, CANCELLING_JOBS, PURGING_PRIMARY, PURGING_OBJECTS, COMPLETED, FAILED;
    val terminal: Boolean get() = this == COMPLETED || this == FAILED
}
