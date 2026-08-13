package com.refinvest.core.strategy.adapter.out.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "strategies")
class StrategyJpaEntity(
    @Id
    var id: Long,
    @Column(name = "member_id", nullable = false)
    var memberId: Long,
    @Column(nullable = false, length = 200)
    var name: String,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant,
)
