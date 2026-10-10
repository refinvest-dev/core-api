package com.refinvest.core.auth.adapter.security.error

data class SecurityErrorResponse(
    val code: String,
    val message: String,
    val traceId: String = java.util.UUID.randomUUID().toString(),
    val retryable: Boolean = false,
)
