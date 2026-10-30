package com.refinvest.core.tradingreview.adapter.persistence

import java.time.Instant

internal data class TradingAccountRecord(
    val id: Long,
    val ownerMemberId: Long,
    val venue: String,
    val displayName: String,
    val status: String,
    val deletionGeneration: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
)
