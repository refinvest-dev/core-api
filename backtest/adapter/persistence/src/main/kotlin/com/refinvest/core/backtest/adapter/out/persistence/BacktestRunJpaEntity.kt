package com.refinvest.core.backtest.adapter.out.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

@Entity
@Table(name = "backtest_runs")
class BacktestRunJpaEntity(
    @Id
    var id: Long,
    @Column(name = "strategy_version_id", nullable = false)
    var strategyVersionId: Long,
    @Column(name = "requested_period_start", nullable = false)
    var requestedPeriodStart: LocalDate,
    @Column(name = "requested_period_end", nullable = false)
    var requestedPeriodEnd: LocalDate,
    @Column(nullable = false, precision = 19, scale = 8)
    var commission: BigDecimal,
    @Column(nullable = false, precision = 19, scale = 8)
    var slippage: BigDecimal,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: BacktestRunStatusJpa,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant,
)

enum class BacktestRunStatusJpa {
    PENDING,
}
