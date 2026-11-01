package com.refinvest.core.tradingreview.adapter.persistence

import java.time.Instant

internal data class DeletionRequestRecord(
    val id: Long, val ownerId: Long, val targetType: String, val targetId: Long, val requestedScope: String,
    val state: String, val deletionGeneration: Long, val accountCount: Long, val bookCount: Long,
    val importSessionCount: Long, val artifactCount: Long, val revokedAt: Instant, val requestedAt: Instant,
    val updatedAt: Instant, val backupPurgeDueAt: Instant, val completedAt: Instant?, val failureCode: String?,
    val attemptCount: Int,
    val version: Long = 0,
    val verifiedAbsent: Map<String, Instant> = emptyMap(),
)
