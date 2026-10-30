package com.refinvest.core.tradingreview.domain.valueobject

import java.time.Instant

data class TradingImportFailure(val code: String, val retryable: Boolean, val userAction: String,
                                val occurredAt: Instant)
