package com.refinvest.core.tradingreview.adapter.web.error

import java.time.Instant

data class TradingErrorResponse(val code: String, val message: String, val traceId: String, val retryable: Boolean,
                                val userAction: String? = null, val occurredAt: Instant? = null)
