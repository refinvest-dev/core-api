package com.refinvest.core.backtest.adapter.out.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "backtest_compute_dispatches")
class BacktestComputeDispatchJpaEntity(
    @Id
    @Column(name = "backtest_run_id")
    var backtestRunId: Long,
    @Column(name = "idempotency_key", nullable = false, unique = true)
    var idempotencyKey: UUID,
    @Column(name = "compute_run_id", unique = true)
    var computeRunId: String? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: BacktestComputeDispatchStatusJpa,
    @Column(name = "next_attempt_at", nullable = false)
    var nextAttemptAt: Instant,
    @Column(name = "lease_token")
    var leaseToken: UUID? = null,
    @Column(name = "lease_expires_at")
    var leaseExpiresAt: Instant? = null,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
)

enum class BacktestComputeDispatchStatusJpa {
    PENDING,
    SUBMITTED,
    REJECTED,
    TERMINAL,
}
