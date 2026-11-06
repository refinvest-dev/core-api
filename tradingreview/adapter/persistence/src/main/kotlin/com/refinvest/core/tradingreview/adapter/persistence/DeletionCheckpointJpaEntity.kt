package com.refinvest.core.tradingreview.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "trading_deletion_checkpoints")
@IdClass(DeletionCheckpointKey::class)
class DeletionCheckpointJpaEntity(
    @Id @Column(name = "request_id", nullable = false, updatable = false)
    var requestId: Long,
    @Id @Column(nullable = false, updatable = false, length = 30)
    var component: String,
    @Column(name = "verified_at", nullable = false, updatable = false)
    var verifiedAt: Instant,
)
