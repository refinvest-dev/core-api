package com.refinvest.core.tradingreview.port.common.ownerquery

import java.time.Instant

sealed interface OwnerMetadataResult {
    data class Account(
        val id: String,
        val venue: String,
        val displayName: String,
        val status: String,
        val createdAt: Instant,
    ) : OwnerMetadataResult

    data class Book(
        val id: String,
        val tradingAccountId: String,
        val venue: String,
        val productFamily: String,
        val positionMode: String,
        val settlementAsset: String,
        val reviewTimezone: String,
        val status: String,
        val createdAt: Instant,
    ) : OwnerMetadataResult

    data class Failure(val kind: OwnerMetadataFailure) : OwnerMetadataResult
}
