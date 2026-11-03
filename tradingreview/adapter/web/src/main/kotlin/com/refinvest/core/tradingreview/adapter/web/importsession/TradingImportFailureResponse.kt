package com.refinvest.core.tradingreview.adapter.web.importsession

import com.refinvest.core.tradingreview.domain.valueobject.TradingImportFailure
import java.time.Instant

data class TradingImportFailureResponse(val code: String, val retryable: Boolean, val userAction: String,
                                        val occurredAt: Instant) {
    companion object {
        fun from(failure: TradingImportFailure) = TradingImportFailureResponse(
            failure.code, failure.retryable, failure.userAction, failure.occurredAt)
    }
}
