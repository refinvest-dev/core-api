package com.refinvest.core.tradingreview.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "trading_books")
class TradingBookJpaEntity(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: Long,
    @Column(name = "account_id", nullable = false, updatable = false)
    var accountId: Long,
    @Column(name = "owner_member_id", nullable = false, updatable = false)
    var ownerMemberId: Long,
    @Column(name = "venue", nullable = false, updatable = false, length = 20)
    var venue: String,
    @Column(name = "display_name", nullable = false, length = 100)
    var displayName: String,
    @Column(name = "product_family", nullable = false, updatable = false, length = 30)
    var productFamily: String,
    @Column(name = "position_mode", nullable = false, updatable = false, length = 20)
    var positionMode: String,
    @Column(name = "settlement_asset", nullable = false, updatable = false, length = 10)
    var settlementAsset: String,
    @Column(name = "review_timezone", nullable = false, updatable = false, length = 100)
    var reviewTimezone: String,
    @Column(name = "status", nullable = false, length = 30)
    var status: String,
    @Column(name = "deletion_generation", nullable = false)
    var deletionGeneration: Long,
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
    @Version
    @Column(name = "version", nullable = false)
    var version: Long,
    @Column(name = "latest_ledger_revision_id", nullable = true, updatable = false)
    var latestLedgerRevisionId: UUID?,
)
