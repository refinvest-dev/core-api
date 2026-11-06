package com.refinvest.core.tradingreview.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant

@Entity
@Table(name = "trading_accounts")
class TradingAccountJpaEntity(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    var id: Long,
    @Column(name = "owner_member_id", nullable = false, updatable = false)
    var ownerMemberId: Long,
    @Column(name = "venue", nullable = false, updatable = false, length = 20)
    var venue: String,
    @Column(name = "display_name", nullable = false, length = 100)
    var displayName: String,
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
)
