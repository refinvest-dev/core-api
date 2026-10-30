package com.refinvest.core.tradingreview.adapter.persistence

import java.time.Instant
import java.util.UUID

internal data class TradingBookRecord(
    val id: Long,
    val accountId: Long,
    val ownerMemberId: Long,
    val venue: String,
    val displayName: String,
    val productFamily: String,
    val positionMode: String,
    val settlementAsset: String,
    val reviewTimezone: String,
    val status: String,
    val deletionGeneration: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
    val latestLedgerRevisionId: UUID?,
)
